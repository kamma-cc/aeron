/*
 * Copyright 2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.archive.status.RecordingPos;
import io.aeron.cluster.codecs.*;
import io.aeron.cluster.service.*;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.Header;
import io.aeron.status.ReadableCounter;
import org.agrona.*;
import org.agrona.collections.ArrayListUtil;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.LongArrayList;
import org.agrona.concurrent.*;
import org.agrona.concurrent.status.CountersReader;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.aeron.ChannelUri.SPY_QUALIFIER;
import static io.aeron.CommonContext.ENDPOINT_PARAM_NAME;
import static io.aeron.archive.client.AeronArchive.NULL_POSITION;
import static io.aeron.cluster.ClusterSession.State.*;
import static io.aeron.cluster.ConsensusModule.Configuration.SESSION_TIMEOUT_MSG;
import static io.aeron.cluster.ConsensusModule.SNAPSHOT_TYPE_ID;

class SequencerAgent implements Agent, ServiceControlListener
{
    private boolean isRecovered;
    private final int memberId;
    private int votedForMemberId = ClusterMember.NULL_MEMBER_ID;
    private int leaderMemberId;
    private int serviceAckCount = 0;
    private int logSessionId;
    private final long sessionTimeoutMs;
    private final long heartbeatIntervalMs;
    private final long heartbeatTimeoutMs;
    private long nextSessionId = 1;
    private long baseLogPosition = 0;
    private long leadershipTermId = -1;
    private long lastRecordingPosition = 0;
    private long timeOfLastLogUpdateMs = 0;
    private long followerCommitPosition = 0;
    private long logRecordingId;
    private ReadableCounter logRecordingPosition;
    private Counter commitPosition;
    private ConsensusModule.State state = ConsensusModule.State.INIT;
    private Cluster.Role role;
    private ClusterMember[] clusterMembers;
    private ClusterMember leaderMember;
    private final ClusterMember thisMember;
    private long[] rankedPositions;
    private final Counter clusterRoleCounter;
    private final ClusterCncFile cncFile;
    private final AgentInvoker aeronClientInvoker;
    private final EpochClock epochClock;
    private final CachedEpochClock cachedEpochClock = new CachedEpochClock();
    private final Counter moduleState;
    private final Counter controlToggle;
    private final TimerService timerService;
    private final ServiceControlAdapter serviceControlAdapter;
    private final ServiceControlPublisher serviceControlPublisher;
    private final IngressAdapter ingressAdapter;
    private final EgressPublisher egressPublisher;
    private final LogAppender logAppender;
    private LogAdapter logAdapter;
    private final MemberStatusAdapter memberStatusAdapter;
    private final MemberStatusPublisher memberStatusPublisher = new MemberStatusPublisher();
    private final Long2ObjectHashMap<ClusterSession> sessionByIdMap = new Long2ObjectHashMap<>();
    private final ArrayList<ClusterSession> pendingSessions = new ArrayList<>();
    private final ArrayList<ClusterSession> rejectedSessions = new ArrayList<>();
    private final Authenticator authenticator;
    private final SessionProxy sessionProxy;
    private final Aeron aeron;
    private AeronArchive archive;
    private final ConsensusModule.Context ctx;
    private final MutableDirectBuffer tempBuffer;
    private final IdleStrategy idleStrategy;
    private final LongArrayList failedTimerCancellations = new LongArrayList();
    private RecordingLog.RecoveryPlan recoveryPlan;

    SequencerAgent(
        final ConsensusModule.Context ctx,
        final EgressPublisher egressPublisher,
        final LogAppender logAppender)
    {
        this.ctx = ctx;
        this.aeron = ctx.aeron();
        this.epochClock = ctx.epochClock();
        this.sessionTimeoutMs = TimeUnit.NANOSECONDS.toMillis(ctx.sessionTimeoutNs());
        this.heartbeatIntervalMs = TimeUnit.NANOSECONDS.toMillis(ctx.heartbeatIntervalNs());
        this.heartbeatTimeoutMs = TimeUnit.NANOSECONDS.toMillis(ctx.heartbeatTimeoutNs());
        this.egressPublisher = egressPublisher;
        this.moduleState = ctx.moduleStateCounter();
        this.controlToggle = ctx.controlToggleCounter();
        this.logAppender = logAppender;
        this.tempBuffer = ctx.tempBuffer();
        this.idleStrategy = ctx.idleStrategy();
        this.timerService = new TimerService(this);
        this.clusterMembers = ClusterMember.parse(ctx.clusterMembers());
        this.sessionProxy = new SessionProxy(egressPublisher);
        this.memberId = ctx.clusterMemberId();
        this.leaderMemberId = ctx.appointedLeaderId();
        this.clusterRoleCounter = ctx.clusterNodeCounter();
        this.cncFile = ctx.clusterCncFile();

        aeronClientInvoker = ctx.ownsAeronClient() ? ctx.aeron().conductorAgentInvoker() : null;
        invokeAeronClient();

        rankedPositions = new long[ClusterMember.quorumThreshold(clusterMembers.length)];
        role(Cluster.Role.FOLLOWER);

        thisMember = clusterMembers[memberId];
        final ChannelUri memberStatusUri = ChannelUri.parse(ctx.memberStatusChannel());
        memberStatusUri.put(ENDPOINT_PARAM_NAME, thisMember.memberFacingEndpoint());

        final int statusStreamId = ctx.memberStatusStreamId();
        memberStatusAdapter = new MemberStatusAdapter(
            aeron.addSubscription(memberStatusUri.toString(), statusStreamId), this);

        ClusterMember.addMemberStatusPublications(clusterMembers, thisMember, memberStatusUri, statusStreamId, aeron);

        final ChannelUri ingressUri = ChannelUri.parse(ctx.ingressChannel());
        if (!ingressUri.containsKey(ENDPOINT_PARAM_NAME))
        {
            ingressUri.put(ENDPOINT_PARAM_NAME, thisMember.clientFacingEndpoint());
        }

        ingressAdapter = new IngressAdapter(
            aeron.addSubscription(ingressUri.toString(), ctx.ingressStreamId()), this, ctx.invalidRequestCounter());

        serviceControlAdapter = new ServiceControlAdapter(
            aeron.addSubscription(ctx.serviceControlChannel(), ctx.serviceControlStreamId()), this);
        serviceControlPublisher = new ServiceControlPublisher(
            aeron.addPublication(ctx.serviceControlChannel(), ctx.serviceControlStreamId()));

        authenticator = ctx.authenticatorSupplier().newAuthenticator(ctx);
    }

    public void onClose()
    {
        CloseHelper.close(archive);

        if (!ctx.ownsAeronClient())
        {
            for (final ClusterSession session : sessionByIdMap.values())
            {
                session.close();
            }

            CloseHelper.close(memberStatusAdapter);
            ClusterMember.closeMemberPublications(clusterMembers);

            logAppender.disconnect();
            CloseHelper.close(ingressAdapter);
            CloseHelper.close(serviceControlPublisher);
            CloseHelper.close(serviceControlAdapter);
        }
    }

    public void onStart()
    {
        archive = AeronArchive.connect(ctx.archiveContext());
        recoveryPlan = ctx.recordingLog().createRecoveryPlan(archive);

        serviceAckCount = 0;
        try (Counter ignore = addRecoveryStateCounter(recoveryPlan))
        {
            if (null != recoveryPlan.snapshotStep)
            {
                recoverFromSnapshot(recoveryPlan.snapshotStep, archive);
            }

            awaitServiceAcks();

            if (recoveryPlan.termSteps.size() > 0)
            {
                recoverFromLog(recoveryPlan.termSteps, archive);
            }

            isRecovered = true;
        }

        state(ConsensusModule.State.ACTIVE); // TODO: handle suspended case
        leadershipTermId++;

        if (clusterMembers.length > 1)
        {
            electLeader();
        }

        if (memberId == leaderMemberId || clusterMembers.length == 1)
        {
            becomeLeader();
        }
        else
        {
            becomeFollower();
        }

        final long nowMs = epochClock.time();
        cachedEpochClock.update(nowMs);
        timeOfLastLogUpdateMs = nowMs;

        ctx.recordingLog().appendTerm(logRecordingId, leadershipTermId, baseLogPosition, nowMs, leaderMemberId);
    }

    public int doWork()
    {
        int workCount = 0;

        boolean isSlowTickCycle = false;
        final long nowMs = epochClock.time();
        if (cachedEpochClock.time() != nowMs)
        {
            isSlowTickCycle = true;
            cachedEpochClock.update(nowMs);
        }

        switch (role)
        {
            case LEADER:
                if (ConsensusModule.State.ACTIVE == state)
                {
                    workCount += ingressAdapter.poll();
                }
                break;

            case FOLLOWER:
                if (ConsensusModule.State.ACTIVE == state || ConsensusModule.State.SUSPENDED == state)
                {
                    workCount += logAdapter.poll(followerCommitPosition);
                }
                break;
        }

        workCount += memberStatusAdapter.poll();
        workCount += updateMemberPosition(nowMs);

        if (isSlowTickCycle)
        {
            workCount += slowTickCycle(nowMs);
        }

        return workCount;
    }

    public String roleName()
    {
        return "sequencer";
    }

    public void onServiceAck(
        final long logPosition, final long leadershipTermId, final int serviceId, final ClusterAction action)
    {
        validateServiceAck(logPosition, leadershipTermId, serviceId, action);

        if (++serviceAckCount == ctx.serviceCount())
        {
            final long termPosition = logPosition - baseLogPosition;

            switch (action)
            {
                case SNAPSHOT:
                    ctx.snapshotCounter().incrementOrdered();
                    state(ConsensusModule.State.ACTIVE);
                    ClusterControl.ToggleState.reset(controlToggle);

                    final long nowNs = epochClock.time();
                    for (final ClusterSession session : sessionByIdMap.values())
                    {
                        session.timeOfLastActivityMs(nowNs);
                    }
                    break;

                case SHUTDOWN:
                    ctx.snapshotCounter().incrementOrdered();
                    ctx.recordingLog().commitLeadershipTermPosition(leadershipTermId, termPosition);
                    state(ConsensusModule.State.CLOSED);
                    ctx.terminationHook().run();
                    break;

                case ABORT:
                    ctx.recordingLog().commitLeadershipTermPosition(leadershipTermId, termPosition);
                    state(ConsensusModule.State.CLOSED);
                    ctx.terminationHook().run();
                    break;
            }
        }
        else if (serviceAckCount > ctx.serviceCount())
        {
            throw new IllegalStateException("Service count exceeded: " + serviceAckCount);
        }
    }

    public void onSessionConnect(
        final long correlationId,
        final int responseStreamId,
        final String responseChannel,
        final byte[] credentialData)
    {
        final long nowMs = cachedEpochClock.time();
        final long sessionId = nextSessionId++;
        final ClusterSession session = new ClusterSession(sessionId, responseStreamId, responseChannel);
        session.connect(aeron);
        session.lastActivity(nowMs, correlationId);

        if (pendingSessions.size() + sessionByIdMap.size() < ctx.maxConcurrentSessions())
        {
            authenticator.onConnectRequest(sessionId, credentialData, nowMs);
            pendingSessions.add(session);
        }
        else
        {
            rejectedSessions.add(session);
        }
    }

    public void onSessionClose(final long clusterSessionId)
    {
        final ClusterSession session = sessionByIdMap.get(clusterSessionId);
        if (null != session)
        {
            session.close();
            if (appendClosedSession(session, CloseReason.USER_ACTION, cachedEpochClock.time()))
            {
                sessionByIdMap.remove(clusterSessionId);
            }
        }
    }

    public ControlledFragmentAssembler.Action onSessionMessage(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final long clusterSessionId,
        final long correlationId)
    {
        final long nowMs = cachedEpochClock.time();
        final ClusterSession session = sessionByIdMap.get(clusterSessionId);
        if (null == session || (session.state() == TIMED_OUT || session.state() == CLOSED))
        {
            return ControlledFragmentHandler.Action.CONTINUE;
        }

        if (session.state() == OPEN && logAppender.appendMessage(buffer, offset, length, nowMs))
        {
            session.lastActivity(nowMs, correlationId);
            return ControlledFragmentHandler.Action.CONTINUE;
        }

        return ControlledFragmentHandler.Action.ABORT;
    }

    public void onKeepAlive(final long clusterSessionId)
    {
        final ClusterSession session = sessionByIdMap.get(clusterSessionId);
        if (null != session)
        {
            session.timeOfLastActivityMs(cachedEpochClock.time());
        }
    }

    public void onChallengeResponse(final long correlationId, final long clusterSessionId, final byte[] credentialData)
    {
        for (int lastIndex = pendingSessions.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final ClusterSession session = pendingSessions.get(i);

            if (session.id() == clusterSessionId && session.state() == CHALLENGED)
            {
                final long nowMs = cachedEpochClock.time();
                session.lastActivity(nowMs, correlationId);
                authenticator.onChallengeResponse(clusterSessionId, credentialData, nowMs);
                break;
            }
        }
    }

    public void onAdminQuery(final long correlationId, final long clusterSessionId, final AdminQueryType queryType)
    {
        final ClusterSession session = sessionByIdMap.get(clusterSessionId);
        if (null != session && session.state() == OPEN)
        {
            switch (queryType)
            {
                case ENDPOINTS:
                    final ChannelUri archiveChannelUri = ChannelUri.parse(ctx.archiveContext().controlRequestChannel());

                    final String endpointsDetail =
                        "id=" + Long.toString(thisMember.id()) +
                        ",memberStatus=" + thisMember.memberFacingEndpoint() +
                        ",log=" + thisMember.memberFacingEndpoint() +
                        ",archive=" + archiveChannelUri.get(ENDPOINT_PARAM_NAME);

                    final long nowMs = cachedEpochClock.time();
                    session.lastActivity(nowMs, correlationId);
                    session.adminQueryResponseDetail(endpointsDetail);

                    if (egressPublisher.sendEvent(session, EventCode.OK, session.adminQueryResponseDetail()))
                    {
                        session.adminQueryResponseDetail(null);
                    }
                    break;

                case RECORDING_LOG: // TODO: or should this really be recoveryPlan?
                    // TODO: send recordingLog as a byte[]
                    break;
            }
        }
    }

    public boolean onTimerEvent(final long correlationId, final long nowMs)
    {
        return logAppender.appendTimerEvent(correlationId, nowMs);
    }

    public void onScheduleTimer(final long correlationId, final long deadlineMs)
    {
        timerService.scheduleTimer(correlationId, deadlineMs);
    }

    public void onCancelTimer(final long correlationId)
    {
        timerService.cancelTimer(correlationId);
    }

    void state(final ConsensusModule.State state)
    {
        this.state = state;
        moduleState.set(state.code());
    }

    void role(final Cluster.Role role)
    {
        this.role = role;
        clusterRoleCounter.setOrdered(role.code());
    }

    void logRecordingPositionCounter(final ReadableCounter logRecordingPosition)
    {
        this.logRecordingPosition = logRecordingPosition;
    }

    void commitPositionCounter(final Counter commitPosition)
    {
        this.commitPosition = commitPosition;
    }

    @SuppressWarnings("unused")
    void onReplaySessionMessage(
        final long correlationId,
        final long clusterSessionId,
        final long timestamp,
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final Header header)
    {
        cachedEpochClock.update(timestamp);
        sessionByIdMap.get(clusterSessionId).lastActivity(timestamp, correlationId);
    }

    void onReplayTimerEvent(final long correlationId, final long timestamp)
    {
        cachedEpochClock.update(timestamp);

        if (!timerService.cancelTimer(correlationId))
        {
            failedTimerCancellations.addLong(correlationId);
        }
    }

    void onReplaySessionOpen(
        final long termPosition,
        final long correlationId,
        final long clusterSessionId,
        final long timestamp,
        final int responseStreamId,
        final String responseChannel)
    {
        cachedEpochClock.update(timestamp);

        final ClusterSession session = new ClusterSession(clusterSessionId, responseStreamId, responseChannel);
        session.open(termPosition);
        session.lastActivity(timestamp, correlationId);

        sessionByIdMap.put(clusterSessionId, session);
        if (clusterSessionId >= nextSessionId)
        {
            nextSessionId = clusterSessionId + 1;
        }
    }

    @SuppressWarnings("unused")
    void onReplaySessionClose(
        final long correlationId,
        final long clusterSessionId,
        final long timestamp,
        final CloseReason closeReason)
    {
        cachedEpochClock.update(timestamp);
        sessionByIdMap.remove(clusterSessionId).close();
    }

    @SuppressWarnings("unused")
    void onReplayClusterAction(
        final long logPosition, final long leadershipTermId, final long timestamp, final ClusterAction action)
    {
        cachedEpochClock.update(timestamp);
        final long termPosition = logPosition - baseLogPosition;

        switch (action)
        {
            case SUSPEND:
                state(ConsensusModule.State.SUSPENDED);
                break;

            case RESUME:
                state(ConsensusModule.State.ACTIVE);
                break;

            case SNAPSHOT:
                if (isRecovered)
                {
                    serviceAckCount = 0;
                    state(ConsensusModule.State.SNAPSHOT);
                    takeSnapshot(timestamp, termPosition);
                }
                break;

            case SHUTDOWN:
                if (isRecovered)
                {
                    serviceAckCount = 0;
                    state(ConsensusModule.State.SHUTDOWN);
                    takeSnapshot(timestamp, termPosition);
                }
                break;

            case ABORT:
                if (isRecovered)
                {
                    serviceAckCount = 0;
                    state(ConsensusModule.State.ABORT);
                }
                break;
        }
    }

    void onReloadState(final long nextSessionId)
    {
        this.nextSessionId = nextSessionId;
    }

    void onRequestVote(
        final long candidateTermId,
        final long lastBaseLogPosition,
        final long lastTermPosition,
        final int candidateMemberId)
    {
        if (Cluster.Role.FOLLOWER == role &&
            candidateTermId == leadershipTermId &&
            lastBaseLogPosition == recoveryPlan.lastLogPosition)
        {
            final boolean potentialLeader = lastTermPosition >= recoveryPlan.lastTermPositionAppended;

            memberStatusPublisher.vote(
                clusterMembers[candidateMemberId].publication(),
                candidateTermId,
                lastBaseLogPosition,
                lastTermPosition,
                candidateMemberId,
                memberId,
                potentialLeader);

            if (!potentialLeader)
            {
                // TODO: become candidate in new election
                throw new IllegalStateException("Invalid member for cluster leader: " + candidateMemberId);
            }
            else
            {
                votedForMemberId = candidateMemberId;

                if (recoveryPlan.lastTermPositionAppended < lastTermPosition)
                {
                    // TODO: need to catch up with leader
                }
            }
        }
        else
        {
            memberStatusPublisher.vote(
                clusterMembers[candidateMemberId].publication(),
                candidateTermId,
                lastBaseLogPosition,
                lastTermPosition,
                candidateMemberId,
                memberId,
                false);
        }
    }

    void onVote(
        final long candidateTermId,
        final long lastBaseLogPosition,
        final long lastTermPosition,
        final int candidateMemberId,
        final int followerMemberId,
        final boolean vote)
    {
        if (Cluster.Role.FOLLOWER == role &&
            candidateTermId == leadershipTermId &&
            lastBaseLogPosition == recoveryPlan.lastLogPosition &&
            lastTermPosition == recoveryPlan.lastTermPositionAppended &&
            candidateMemberId == memberId)
        {
            if (vote)
            {
                clusterMembers[followerMemberId].votedForId(candidateMemberId);
            }
            else
            {
                throw new IllegalStateException("Invalid member for cluster leader: " + candidateMemberId);
            }
        }
    }

    void onAppendedPosition(final long termPosition, final long leadershipTermId, final int followerMemberId)
    {
        if (leadershipTermId == this.leadershipTermId)
        {
            clusterMembers[followerMemberId].termPosition(termPosition);
        }
    }

    void onCommitPosition(
        final long termPosition, final long leadershipTermId, final int leaderMemberId, final int logSessionId)
    {
        if (leadershipTermId == this.leadershipTermId)
        {
            if (leaderMemberId != this.leaderMemberId)
            {
                throw new IllegalStateException("Commit position not for current leader: expected=" +
                    this.leaderMemberId + " received=" + leaderMemberId);
            }

            if (0 == termPosition && leaderMemberId == votedForMemberId && this.logSessionId != logSessionId)
            {
                this.logSessionId = logSessionId;
            }

            timeOfLastLogUpdateMs = cachedEpochClock.time();
            followerCommitPosition = termPosition;
        }
    }

    private int slowTickCycle(final long nowMs)
    {
        int workCount = 0;

        cncFile.updateActivityTimestamp(nowMs);
        workCount += invokeAeronClient();
        workCount += serviceControlAdapter.poll();

        if (Cluster.Role.LEADER == role)
        {
            workCount += checkControlToggle(nowMs);

            if (ConsensusModule.State.ACTIVE == state)
            {
                workCount += processPendingSessions(pendingSessions, nowMs);
                workCount += checkSessions(sessionByIdMap, nowMs);
                workCount += processRejectedSessions(rejectedSessions, nowMs);
                workCount += timerService.poll(nowMs);
            }
        }

        if (null != archive)
        {
            archive.checkForErrorResponse();
        }

        return workCount;
    }

    private int checkControlToggle(final long nowMs)
    {
        switch (ClusterControl.ToggleState.get(controlToggle))
        {
            case SUSPEND:
                if (ConsensusModule.State.ACTIVE == state && appendAction(ClusterAction.SUSPEND, nowMs))
                {
                    state(ConsensusModule.State.SUSPENDED);
                    ClusterControl.ToggleState.reset(controlToggle);
                }
                break;

            case RESUME:
                if (ConsensusModule.State.SUSPENDED == state && appendAction(ClusterAction.RESUME, nowMs))
                {
                    state(ConsensusModule.State.ACTIVE);
                    ClusterControl.ToggleState.reset(controlToggle);
                }
                break;

            case SNAPSHOT:
                serviceAckCount = 0;
                if (ConsensusModule.State.ACTIVE == state && appendAction(ClusterAction.SNAPSHOT, nowMs))
                {
                    state(ConsensusModule.State.SNAPSHOT);
                    takeSnapshot(nowMs, logAppender.position());
                }
                break;

            case SHUTDOWN:
                serviceAckCount = 0;
                if (ConsensusModule.State.ACTIVE == state && appendAction(ClusterAction.SHUTDOWN, nowMs))
                {
                    state(ConsensusModule.State.SHUTDOWN);
                    takeSnapshot(nowMs, logAppender.position());
                }
                break;

            case ABORT:
                serviceAckCount = 0;
                if (ConsensusModule.State.ACTIVE == state && appendAction(ClusterAction.ABORT, nowMs))
                {
                    state(ConsensusModule.State.ABORT);
                }
                break;

            default:
                return 0;
        }

        return 1;
    }

    private boolean appendAction(final ClusterAction action, final long nowMs)
    {
        final long position = baseLogPosition +
            logAppender.position() +
            MessageHeaderEncoder.ENCODED_LENGTH +
            ClusterActionRequestEncoder.BLOCK_LENGTH;

        return logAppender.appendClusterAction(action, leadershipTermId, position, nowMs);
    }

    private int processPendingSessions(final ArrayList<ClusterSession> pendingSessions, final long nowMs)
    {
        int workCount = 0;

        for (int lastIndex = pendingSessions.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final ClusterSession session = pendingSessions.get(i);

            if (session.state() == INIT || session.state() == CONNECTED)
            {
                if (session.isResponsePublicationConnected())
                {
                    session.state(CONNECTED);
                    authenticator.onProcessConnectedSession(sessionProxy.session(session), nowMs);
                }
            }

            if (session.state() == CHALLENGED)
            {
                if (session.isResponsePublicationConnected())
                {
                    authenticator.onProcessChallengedSession(sessionProxy.session(session), nowMs);
                }
            }

            if (session.state() == AUTHENTICATED)
            {
                ArrayListUtil.fastUnorderedRemove(pendingSessions, i, lastIndex--);
                session.timeOfLastActivityMs(nowMs);
                sessionByIdMap.put(session.id(), session);
                appendConnectedSession(session, nowMs);

                workCount += 1;
            }
            else if (session.state() == REJECTED)
            {
                ArrayListUtil.fastUnorderedRemove(pendingSessions, i, lastIndex--);
                rejectedSessions.add(session);
            }
            else if (nowMs > (session.timeOfLastActivityMs() + sessionTimeoutMs))
            {
                ArrayListUtil.fastUnorderedRemove(pendingSessions, i, lastIndex--);
                session.close();
            }
        }

        return workCount;
    }

    private int processRejectedSessions(final ArrayList<ClusterSession> rejectedSessions, final long nowMs)
    {
        int workCount = 0;

        for (int lastIndex = rejectedSessions.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final ClusterSession session = rejectedSessions.get(i);
            String detail = ConsensusModule.Configuration.SESSION_LIMIT_MSG;
            EventCode eventCode = EventCode.ERROR;

            if (session.state() == REJECTED)
            {
                detail = ConsensusModule.Configuration.SESSION_REJECTED_MSG;
                eventCode = EventCode.AUTHENTICATION_REJECTED;
            }

            if (egressPublisher.sendEvent(session, eventCode, detail) ||
                nowMs > (session.timeOfLastActivityMs() + sessionTimeoutMs))
            {
                ArrayListUtil.fastUnorderedRemove(rejectedSessions, i, lastIndex--);
                session.close();
                workCount++;
            }
        }

        return workCount;
    }

    private int checkSessions(final Long2ObjectHashMap<ClusterSession> sessionByIdMap, final long nowMs)
    {
        int workCount = 0;

        for (final Iterator<ClusterSession> i = sessionByIdMap.values().iterator(); i.hasNext(); )
        {
            final ClusterSession session = i.next();
            final ClusterSession.State state = session.state();

            if (nowMs > (session.timeOfLastActivityMs() + sessionTimeoutMs))
            {
                switch (state)
                {
                    case OPEN:
                        egressPublisher.sendEvent(session, EventCode.ERROR, SESSION_TIMEOUT_MSG);
                        if (appendClosedSession(session, CloseReason.TIMEOUT, nowMs))
                        {
                            session.close();
                            i.remove();
                        }
                        else
                        {
                            session.state(TIMED_OUT);
                        }
                        break;

                    case TIMED_OUT:
                    case CLOSED:
                        final CloseReason reason = state == TIMED_OUT ? CloseReason.TIMEOUT : CloseReason.USER_ACTION;
                        if (appendClosedSession(session, reason, nowMs))
                        {
                            session.close();
                            i.remove();
                        }
                        break;

                    default:
                        session.close();
                        i.remove();
                }

                workCount += 1;
            }
            else if (state == CONNECTED)
            {
                appendConnectedSession(session, nowMs);
                workCount += 1;
            }
            else if (state == OPEN && session.adminQueryResponseDetail() != null)
            {
                if (egressPublisher.sendEvent(session, EventCode.OK, session.adminQueryResponseDetail()))
                {
                    session.adminQueryResponseDetail(null);
                }
            }
        }

        return workCount;
    }

    private void appendConnectedSession(final ClusterSession session, final long nowMs)
    {
        final long resultingPosition = logAppender.appendConnectedSession(session, nowMs);
        if (resultingPosition > 0)
        {
            session.open(resultingPosition);
        }
    }

    private boolean appendClosedSession(final ClusterSession session, final CloseReason closeReason, final long nowMs)
    {
        if (logAppender.appendClosedSession(session, closeReason, nowMs))
        {
            session.close();
            return true;
        }

        return false;
    }

    private void electLeader()
    {
        awaitConnectedMembers();

        if (ctx.appointedLeaderId() == memberId)
        {
            role(Cluster.Role.CANDIDATE);
            ClusterMember.becomeCandidate(clusterMembers, memberId);
            votedForMemberId = memberId;

            for (final ClusterMember member : clusterMembers)
            {
                if (!memberStatusPublisher.requestVote(
                    member.publication(),
                    leadershipTermId,
                    recoveryPlan.lastLogPosition,
                    recoveryPlan.lastTermPositionAppended,
                    memberId))
                {
                    throw new IllegalStateException("failed to request vote");
                }
            }

            do
            {
                idle(memberStatusAdapter.poll());
            }
            while (ClusterMember.awaitingVotes(clusterMembers));

            leaderMemberId = memberId;
            leaderMember = thisMember;
        }
        else
        {
            votedForMemberId = ClusterMember.NULL_MEMBER_ID;

            do
            {
                idle(memberStatusAdapter.poll());
            }
            while (ClusterMember.NULL_MEMBER_ID == leaderMemberId);
        }
    }

    private void awaitConnectedMembers()
    {
        idleStrategy.reset();
        while (true)
        {
            if (ClusterMember.arePublicationsConnected(clusterMembers))
            {
                break;
            }
            idle();
        }
    }

    private void becomeLeader()
    {
        updateMemberDetails(leaderMemberId);
        role(Cluster.Role.LEADER);

        final ChannelUri channelUri = ChannelUri.parse(ctx.logChannel());
        final Publication publication = aeron.addExclusivePublication(ctx.logChannel(), ctx.logStreamId());
        if (!channelUri.containsKey(CommonContext.ENDPOINT_PARAM_NAME))
        {
            final ChannelUriStringBuilder builder = new ChannelUriStringBuilder().media("udp");
            for (final ClusterMember member : clusterMembers)
            {
                if (member.id() != memberId)
                {
                    publication.addDestination(builder.endpoint(member.logEndpoint()).build());
                }
            }
        }

        logAdapter = null;
        logAppender.connect(publication);
        logSessionId = publication.sessionId();

        channelUri.put(CommonContext.SESSION_ID_PARAM_NAME, Integer.toString(logSessionId));
        final String recordingChannel = channelUri.toString();
        archive.startRecording(recordingChannel, ctx.logStreamId(), SourceLocation.LOCAL);

        createPositionCounters();

        awaitServicesReady(channelUri, true);
        awaitFollowersReady();

        final long nowMs = epochClock.time();
        for (final ClusterSession session : sessionByIdMap.values())
        {
            session.connect(aeron);
            session.timeOfLastActivityMs(nowMs);
        }
    }

    private void becomeFollower()
    {
        leaderMember = clusterMembers[leaderMemberId];
        followerCommitPosition = NULL_POSITION;

        updateMemberDetails(leaderMemberId);
        role(Cluster.Role.FOLLOWER);

        while (NULL_POSITION == followerCommitPosition)
        {
            final int fragments = memberStatusAdapter.poll();
            idle(fragments);
        }

        final ChannelUri channelUri = ChannelUri.parse(ctx.logChannel());
        channelUri.put(CommonContext.ENDPOINT_PARAM_NAME, thisMember.logEndpoint());
        channelUri.put(CommonContext.SESSION_ID_PARAM_NAME, Integer.toString(logSessionId));
        final String logChannel = channelUri.toString();

        final int streamId = ctx.logStreamId();
        archive.startRecording(logChannel, streamId, SourceLocation.REMOTE);
        logAdapter = new LogAdapter(awaitImage(logSessionId, aeron.addSubscription(logChannel, streamId)), this);

        createPositionCounters();
        awaitServicesReady(channelUri, false);
    }

    private void awaitFollowersReady()
    {
        ClusterMember.resetTermPositions(clusterMembers, -1);
        clusterMembers[memberId].termPosition(logRecordingPosition.get());

        do
        {
            final long nowMs = epochClock.time();
            if (nowMs > (timeOfLastLogUpdateMs + heartbeatIntervalMs))
            {
                timeOfLastLogUpdateMs = nowMs;

                for (final ClusterMember member : clusterMembers)
                {
                    if (member != thisMember)
                    {
                        memberStatusPublisher.commitPosition(
                            member.publication(), baseLogPosition, leadershipTermId, memberId, logSessionId);
                    }
                }
            }

            idle(memberStatusAdapter.poll());
        }
        while (!ClusterMember.hasReachedPosition(clusterMembers, 0));
    }

    private void createPositionCounters()
    {
        final CountersReader counters = aeron.countersReader();
        final int recordingCounterId = awaitRecordingCounter(counters, logSessionId);

        logRecordingPosition = new ReadableCounter(counters, recordingCounterId);
        logRecordingId = RecordingPos.getRecordingId(counters, logRecordingPosition.counterId());

        commitPosition = CommitPos.allocate(
            aeron, tempBuffer, logRecordingId, baseLogPosition, leadershipTermId, logSessionId);
    }

    private void awaitServicesReady(final ChannelUri channelUri, final boolean isLeader)
    {
        serviceAckCount = 0;

        final String channel = isLeader ? channelUri.prefix(SPY_QUALIFIER).toString() : channelUri.toString();
        serviceControlPublisher.joinLog(
            leadershipTermId, commitPosition.id(), logSessionId, ctx.logStreamId(), channel);

        awaitServiceAcks();
    }

    private void updateMemberDetails(final int leaderMemberId)
    {
        for (final ClusterMember clusterMember : clusterMembers)
        {
            clusterMember.isLeader(clusterMember.id() == leaderMemberId);
        }

        updateClusterMemberDetails(clusterMembers);
    }

    private void recoverFromSnapshot(final RecordingLog.ReplayStep snapshotStep, final AeronArchive archive)
    {
        final RecordingLog.Entry snapshot = snapshotStep.entry;

        cachedEpochClock.update(snapshot.timestamp);
        baseLogPosition = snapshot.logPosition;
        leadershipTermId = snapshot.leadershipTermId;

        final long recordingId = snapshot.recordingId;
        final RecordingExtent recordingExtent = new RecordingExtent();
        if (0 == archive.listRecording(recordingId, recordingExtent))
        {
            throw new IllegalStateException("Could not find recordingId: " + recordingId);
        }

        final String channel = ctx.replayChannel();
        final int streamId = ctx.replayStreamId();

        final long length = recordingExtent.stopPosition - recordingExtent.startPosition;
        final int sessionId = (int)archive.startReplay(recordingId, 0, length, channel, streamId);

        final String replaySubscriptionChannel = ChannelUri.addSessionId(channel, sessionId);
        try (Subscription subscription = aeron.addSubscription(replaySubscriptionChannel, streamId))
        {
            final Image image = awaitImage(sessionId, subscription);

            final SnapshotLoader snapshotLoader = new SnapshotLoader(image, this);
            while (true)
            {
                final int fragments = snapshotLoader.poll();
                if (fragments == 0)
                {
                    if (snapshotLoader.isDone())
                    {
                        break;
                    }

                    if (image.isClosed())
                    {
                        throw new IllegalStateException("Snapshot ended unexpectedly");
                    }
                }

                idle(fragments);
            }
        }
    }

    private Image awaitImage(final int sessionId, final Subscription subscription)
    {
        idleStrategy.reset();
        Image image;
        while ((image = subscription.imageBySessionId(sessionId)) == null)
        {
            idle();
        }

        return image;
    }

    private void recoverFromLog(final List<RecordingLog.ReplayStep> steps, final AeronArchive archive)
    {
        final int streamId = ctx.replayStreamId();
        final ChannelUri channelUri = ChannelUri.parse(ctx.replayChannel());

        for (int i = 0, size = steps.size(); i < size; i++)
        {
            final RecordingLog.ReplayStep step = steps.get(i);
            final RecordingLog.Entry entry = step.entry;
            final long recordingId = entry.recordingId;
            final long startPosition = step.recordingStartPosition;
            final long stopPosition = step.recordingStopPosition;
            final long length = NULL_POSITION == stopPosition ? Long.MAX_VALUE : stopPosition - startPosition;
            final long logPosition = entry.logPosition;

            if (logPosition != baseLogPosition)
            {
                throw new IllegalStateException("base position for log not as expected: expected " +
                    baseLogPosition + " actual is " + logPosition + ", " + step);
            }

            leadershipTermId = entry.leadershipTermId;

            channelUri.put(CommonContext.SESSION_ID_PARAM_NAME, Integer.toString(i));
            final String channel = channelUri.toString();

            try (Counter counter = CommitPos.allocate(
                aeron, tempBuffer, recordingId, logPosition, leadershipTermId, i);
                Subscription subscription = aeron.addSubscription(channel, streamId))
            {
                counter.setOrdered(stopPosition);

                serviceAckCount = 0;
                serviceControlPublisher.joinLog(leadershipTermId, counter.id(), i, streamId, channel);
                awaitServiceAcks();

                final int sessionId = (int)archive.startReplay(recordingId, startPosition, length, channel, streamId);

                if (i != sessionId)
                {
                    throw new IllegalStateException("Session id not for iteration: " + sessionId);
                }

                final Image image = awaitImage(sessionId, subscription);

                serviceAckCount = 0;
                replayTerm(image, stopPosition);
                awaitServiceAcks();

                final long termPosition = image.position();
                if (step.entry.termPosition < termPosition)
                {
                    ctx.recordingLog().commitLeadershipTermPosition(leadershipTermId, termPosition);
                }

                baseLogPosition += termPosition;

                failedTimerCancellations.forEachOrderedLong(timerService::cancelTimer);
                failedTimerCancellations.clear();
            }
        }

        failedTimerCancellations.trimToSize();
    }

    private Counter addRecoveryStateCounter(final RecordingLog.RecoveryPlan plan)
    {
        final int termCount = plan.termSteps.size();
        final RecordingLog.ReplayStep snapshotStep = plan.snapshotStep;

        if (null != snapshotStep)
        {
            final RecordingLog.Entry snapshot = snapshotStep.entry;

            return RecoveryState.allocate(
                aeron, tempBuffer, snapshot.leadershipTermId, snapshot.termPosition, snapshot.timestamp, termCount);
        }

        return RecoveryState.allocate(aeron, tempBuffer, leadershipTermId, NULL_POSITION, 0, termCount);
    }

    private void awaitServiceAcks()
    {
        while (true)
        {
            final int fragmentsRead = serviceControlAdapter.poll();
            if (serviceAckCount >= ctx.serviceCount())
            {
                break;
            }

            idle(fragmentsRead);
        }
    }

    private void validateServiceAck(
        final long logPosition, final long leadershipTermId, final int serviceId, final ClusterAction action)
    {
        final long currentLogPosition = baseLogPosition + currentTermPosition();
        if (logPosition != currentLogPosition || leadershipTermId != this.leadershipTermId)
        {
            throw new IllegalStateException("Invalid log state:" +
                " serviceId=" + serviceId +
                ", logPosition=" + logPosition + " current is " + currentLogPosition +
                ", leadershipTermId=" + leadershipTermId + " current is " + this.leadershipTermId);
        }

        if (!state.isValid(action))
        {
            throw new IllegalStateException("Invalid action ack for state " + state + " action " + action);
        }
    }

    private long currentTermPosition()
    {
        return null != logAdapter ? logAdapter.position() : logAppender.position();
    }

    private void updateClusterMemberDetails(final ClusterMember[] members)
    {
        int leaderIndex = 0;
        for (int i = 0, length = members.length; i < length; i++)
        {
            if (members[i].isLeader())
            {
                leaderIndex = i;
                break;
            }
        }

        final StringBuilder builder = new StringBuilder(100);
        builder.append(members[leaderIndex].clientFacingEndpoint());

        for (int i = 0, length = members.length; i < length; i++)
        {
            if (i != leaderIndex)
            {
                builder.append(',').append(members[i].clientFacingEndpoint());
            }
        }

        sessionProxy.memberEndpointsDetail(builder.toString());
    }

    private int updateMemberPosition(final long nowMs)
    {
        int workCount = 0;

        switch (role)
        {
            case LEADER:
            {
                thisMember.termPosition(logRecordingPosition.get());

                final long position = ClusterMember.quorumPosition(clusterMembers, rankedPositions);
                if (position > commitPosition.getWeak() || nowMs >= (timeOfLastLogUpdateMs + heartbeatIntervalMs))
                {
                    for (final ClusterMember member : clusterMembers)
                    {
                        if (member != thisMember)
                        {
                            memberStatusPublisher.commitPosition(
                                member.publication(), position, leadershipTermId, memberId, logSessionId);
                        }
                    }

                    commitPosition.setOrdered(position);
                    timeOfLastLogUpdateMs = nowMs;

                    workCount = 1;
                }
                break;
            }

            case FOLLOWER:
            {
                final long recordingPosition = logRecordingPosition.get();
                if (recordingPosition != lastRecordingPosition)
                {
                    final Publication publication = leaderMember.publication();
                    if (memberStatusPublisher.appendedPosition(
                        publication, recordingPosition, leadershipTermId, memberId))
                    {
                        lastRecordingPosition = recordingPosition;
                    }

                    workCount = 1;
                }

                commitPosition.proposeMaxOrdered(logAdapter.position());

                if (nowMs >= (timeOfLastLogUpdateMs + heartbeatTimeoutMs))
                {
                    throw new AgentTerminationException("No heartbeat detected from cluster leader");
                }
                break;
            }
        }

        return workCount;
    }

    private void idle()
    {
        checkInterruptedStatus();
        invokeAeronClient();
        idleStrategy.idle();
    }

    private void idle(final int workCount)
    {
        checkInterruptedStatus();
        invokeAeronClient();
        idleStrategy.idle(workCount);
    }

    private static void checkInterruptedStatus()
    {
        if (Thread.currentThread().isInterrupted())
        {
            throw new RuntimeException("Unexpected interrupt");
        }
    }

    private int invokeAeronClient()
    {
        int workCount = 0;

        if (null != aeronClientInvoker)
        {
            workCount += aeronClientInvoker.invoke();
        }

        return workCount;
    }

    private void takeSnapshot(final long timestampMs, final long termPosition)
    {
        final long recordingId;
        final long logPosition = baseLogPosition + termPosition;
        final String channel = ctx.snapshotChannel();
        final int streamId = ctx.snapshotStreamId();

        try (Publication publication = archive.addRecordedExclusivePublication(channel, streamId))
        {
            try
            {
                final CountersReader counters = aeron.countersReader();
                final int counterId = awaitRecordingCounter(counters, publication.sessionId());
                recordingId = RecordingPos.getRecordingId(counters, counterId);

                snapshotState(publication, logPosition, leadershipTermId);
                awaitRecordingComplete(recordingId, publication.position(), counters, counterId);
            }
            finally
            {
                archive.stopRecording(publication);
            }
        }

        ctx.recordingLog().appendSnapshot(recordingId, leadershipTermId, baseLogPosition, termPosition, timestampMs);
    }

    private void awaitRecordingComplete(
        final long recordingId, final long completePosition, final CountersReader counters, final int counterId)
    {
        idleStrategy.reset();
        do
        {
            idle();

            if (!RecordingPos.isActive(counters, counterId, recordingId))
            {
                throw new IllegalStateException("Recording has stopped unexpectedly: " + recordingId);
            }
        }
        while (counters.getCounterValue(counterId) < completePosition);
    }

    private int awaitRecordingCounter(final CountersReader counters, final int sessionId)
    {
        idleStrategy.reset();
        int counterId = RecordingPos.findCounterIdBySession(counters, sessionId);
        while (CountersReader.NULL_COUNTER_ID == counterId)
        {
            idle();
            counterId = RecordingPos.findCounterIdBySession(counters, sessionId);
        }

        return counterId;
    }

    private void snapshotState(final Publication publication, final long logPosition, final long leadershipTermId)
    {
        final ConsensusModuleSnapshotTaker snapshotTaker = new ConsensusModuleSnapshotTaker(
            publication, idleStrategy, aeronClientInvoker);

        snapshotTaker.markBegin(SNAPSHOT_TYPE_ID, logPosition, leadershipTermId, 0);

        for (final ClusterSession session : sessionByIdMap.values())
        {
            if (session.state() == OPEN)
            {
                snapshotTaker.snapshotSession(session);
            }
        }

        invokeAeronClient();

        timerService.snapshot(snapshotTaker);
        snapshotTaker.sequencerState(nextSessionId);

        snapshotTaker.markEnd(SNAPSHOT_TYPE_ID, logPosition, leadershipTermId, 0);
    }

    private void replayTerm(final Image image, final long finalTermPosition)
    {
        logAdapter = new LogAdapter(image, this);

        while (true)
        {
            final int fragments = logAdapter.poll(finalTermPosition);
            if (fragments == 0)
            {
                if (image.isClosed())
                {
                    if (!image.isEndOfStream())
                    {
                        throw new IllegalStateException("Unexpected close");
                    }

                    break;
                }
            }

            idle(fragments);
        }
    }
}

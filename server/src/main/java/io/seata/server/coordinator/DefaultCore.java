/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.server.coordinator;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alipay.remoting.exception.CodecException;
import com.alipay.remoting.serialization.SerializerManager;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.entity.Task;

import io.seata.common.exception.NotSupportYetException;
import io.seata.common.loader.EnhancedServiceLoader;
import io.seata.common.util.CollectionUtils;
import io.seata.common.util.StringUtils;
import io.seata.core.event.EventBus;
import io.seata.core.event.GlobalTransactionEvent;
import io.seata.core.exception.TransactionException;
import io.seata.core.logger.StackTraceLogger;
import io.seata.core.model.BranchStatus;
import io.seata.core.model.BranchType;
import io.seata.core.model.GlobalStatus;
import io.seata.core.raft.RaftServerFactory;
import io.seata.core.rpc.RemotingServer;
import io.seata.server.event.EventBusManager;
import io.seata.server.session.BranchSession;
import io.seata.server.session.GlobalSession;
import io.seata.server.session.SessionHelper;
import io.seata.server.session.SessionHolder;

import static com.alipay.remoting.serialization.SerializerManager.Hessian2;
import static io.seata.core.raft.msg.RaftSyncMsg.MsgType.DO_COMMIT;
import static io.seata.core.raft.msg.RaftSyncMsg.MsgType.DO_ROLLBACK;

/**
 * The type Default core.
 *
 * @author sharajava
 */
public class DefaultCore implements Core {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultCore.class);

    private EventBus eventBus = EventBusManager.get();

    private static Map<BranchType, AbstractCore> coreMap = new ConcurrentHashMap<>();

    /**
     * get the Default core.
     *
     * @param remotingServer the remoting server
     */
    public DefaultCore(RemotingServer remotingServer) {
        List<AbstractCore> allCore = EnhancedServiceLoader.loadAll(AbstractCore.class,
            new Class[]{RemotingServer.class}, new Object[]{remotingServer});
        if (CollectionUtils.isNotEmpty(allCore)) {
            for (AbstractCore core : allCore) {
                coreMap.put(core.getHandleBranchType(), core);
            }
        }
    }

    /**
     * get core
     *
     * @param branchType the branchType
     * @return the core
     */
    public AbstractCore getCore(BranchType branchType) {
        AbstractCore core = coreMap.get(branchType);
        if (core == null) {
            throw new NotSupportYetException("unsupported type:" + branchType.name());
        }
        return core;
    }

    /**
     * only for mock
     *
     * @param branchType the branchType
     * @param core       the core
     */
    public void mockCore(BranchType branchType, AbstractCore core) {
        coreMap.put(branchType, core);
    }

    @Override
    public Long branchRegister(BranchType branchType, String resourceId, String clientId, String xid,
                               String applicationData, String lockKeys) throws TransactionException {
        return getCore(branchType).branchRegister(branchType, resourceId, clientId, xid,
            applicationData, lockKeys);
    }
    
    @Override
    public Long branchRegister(BranchType branchType, String resourceId, String clientId, String xid,
        String applicationData, String lockKeys, Long branchId) throws TransactionException {
        return getCore(branchType).branchRegister(branchType, resourceId, clientId, xid, applicationData, lockKeys,
            branchId);
    }

    @Override
    public void branchReport(BranchType branchType, String xid, long branchId, BranchStatus status,
                             String applicationData) throws TransactionException {
        getCore(branchType).branchReport(branchType, xid, branchId, status, applicationData);
    }

    @Override
    public boolean lockQuery(BranchType branchType, String resourceId, String xid, String lockKeys)
        throws TransactionException {
        return getCore(branchType).lockQuery(branchType, resourceId, xid, lockKeys);
    }

    @Override
    public BranchStatus branchCommit(GlobalSession globalSession, BranchSession branchSession) throws TransactionException {
        return getCore(branchSession.getBranchType()).branchCommit(globalSession, branchSession);
    }

    @Override
    public BranchStatus branchRollback(GlobalSession globalSession, BranchSession branchSession) throws TransactionException {
        LOGGER.info("branchRollback");
        return getCore(branchSession.getBranchType()).branchRollback(globalSession, branchSession);
    }

    @Override
    public String begin(String applicationId, String transactionServiceGroup, String name, int timeout)
        throws TransactionException {
        return begin(null, applicationId, transactionServiceGroup, name, timeout);
    }

    @Override
    public String begin(String xid, String applicationId, String transactionServiceGroup, String name, int timeout)
        throws TransactionException {
        GlobalSession session;
        if (StringUtils.isBlank(xid)) {
            session = GlobalSession.createGlobalSession(applicationId, transactionServiceGroup, name, timeout);
        } else {
            session = GlobalSession.createGlobalSession(xid, applicationId, transactionServiceGroup, name, timeout);
        }
        session.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
        session.begin();

        // transaction start event
        eventBus.post(new GlobalTransactionEvent(session.getTransactionId(), GlobalTransactionEvent.ROLE_TC,
            session.getTransactionName(), session.getBeginTime(), null, session.getStatus()));

        return session.getXid();
    }

    @Override
    public GlobalStatus commit(String xid) throws TransactionException {
        GlobalSession globalSession = SessionHolder.findGlobalSession(xid);
        if (globalSession == null) {
            return GlobalStatus.Finished;
        }
        globalSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
        // just lock changeStatus

        boolean shouldCommit = SessionHolder.lockAndExecute(globalSession, () -> {
            // Highlight: Firstly, close the session, then no more branch can be registered.
            globalSession.closeAndClean();
            if (globalSession.getStatus() == GlobalStatus.Begin) {
                if (globalSession.canBeCommittedAsync()) {
                    globalSession.asyncCommit();
                    return false;
                } else {
                    globalSession.changeStatus(GlobalStatus.Committing);
                    return true;
                }
            }
            return false;
        });

        if (shouldCommit) {
            boolean success = doGlobalCommit(globalSession, false);
            if (success && !globalSession.getBranchSessions().isEmpty()) {
                globalSession.asyncCommit();
                return GlobalStatus.Committed;
            } else {
                return globalSession.getStatus();
            }
        } else {
            return globalSession.getStatus() == GlobalStatus.AsyncCommitting ? GlobalStatus.Committed : globalSession.getStatus();
        }
    }

    @Override
    public boolean doGlobalCommit(GlobalSession globalSession, boolean retrying) throws TransactionException {
        if (RaftServerFactory.getInstance().getRaftServer() != null) {
            return doRaftGlobalCommit(globalSession, retrying);
        }
        boolean success = true;
        // start committing event
        eventBus.post(new GlobalTransactionEvent(globalSession.getTransactionId(), GlobalTransactionEvent.ROLE_TC,
            globalSession.getTransactionName(), globalSession.getBeginTime(), null, globalSession.getStatus()));

        if (globalSession.isSaga()) {
            success = getCore(BranchType.SAGA).doGlobalCommit(globalSession, retrying);
        } else {
            for (BranchSession branchSession : globalSession.getSortedBranches()) {
                // if not retrying, skip the canBeCommittedAsync branches
                if (!retrying && branchSession.canBeCommittedAsync()) {
                    continue;
                }

                BranchStatus currentStatus = branchSession.getStatus();
                if (currentStatus == BranchStatus.PhaseOne_Failed) {
                    globalSession.removeBranch(branchSession);
                    continue;
                }
                try {
                    BranchStatus branchStatus = getCore(branchSession.getBranchType()).branchCommit(globalSession, branchSession);

                    switch (branchStatus) {
                        case PhaseTwo_Committed:
                            globalSession.removeBranch(branchSession);
                            continue;
                        case PhaseTwo_CommitFailed_Unretryable:
                            if (globalSession.canBeCommittedAsync()) {
                                LOGGER.error(
                                    "Committing branch transaction[{}], status: PhaseTwo_CommitFailed_Unretryable, please check the business log.", branchSession.getBranchId());
                                continue;
                            } else {
                                SessionHelper.endCommitFailed(globalSession);
                                LOGGER.error("Committing global transaction[{}] finally failed, caused by branch transaction[{}] commit failed.", globalSession.getXid(), branchSession.getBranchId());
                                return false;
                            }
                        default:
                            if (!retrying) {
                                globalSession.queueToRetryCommit();
                                return false;
                            }
                            if (globalSession.canBeCommittedAsync()) {
                                LOGGER.error("Committing branch transaction[{}], status:{} and will retry later",
                                    branchSession.getBranchId(), branchStatus);
                                continue;
                            } else {
                                LOGGER.error(
                                    "Committing global transaction[{}] failed, caused by branch transaction[{}] commit failed, will retry later.", globalSession.getXid(), branchSession.getBranchId());
                                return false;
                            }
                    }
                } catch (Exception ex) {
                    StackTraceLogger.error(LOGGER, ex, "Committing branch transaction exception: {}",
                        new String[] {branchSession.toString()});
                    if (!retrying) {
                        globalSession.queueToRetryCommit();
                        throw new TransactionException(ex);
                    }
                }
            }
            if (globalSession.hasBranch()) {
                LOGGER.info("Committing global transaction is NOT done, xid = {}.", globalSession.getXid());
                return false;
            }
        }
        if (success && globalSession.getBranchSessions().isEmpty()) {
            SessionHelper.endCommitted(globalSession);

            // committed event
            eventBus.post(new GlobalTransactionEvent(globalSession.getTransactionId(), GlobalTransactionEvent.ROLE_TC,
                globalSession.getTransactionName(), globalSession.getBeginTime(), System.currentTimeMillis(),
                globalSession.getStatus()));

            LOGGER.info("Committing global transaction is successfully done, xid = {}.", globalSession.getXid());
        }
        return success;
    }


    @Override
    public GlobalStatus rollback(String xid) throws TransactionException {
        GlobalSession globalSession = SessionHolder.findGlobalSession(xid);
        if (globalSession == null) {
            return GlobalStatus.Finished;
        }
        globalSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
        // just lock changeStatus
        boolean shouldRollBack = SessionHolder.lockAndExecute(globalSession, () -> {
            globalSession.close(); // Highlight: Firstly, close the session, then no more branch can be registered.
            if (globalSession.getStatus() == GlobalStatus.Begin) {
                globalSession.changeStatus(GlobalStatus.Rollbacking);
                return true;
            }
            return false;
        });
        if (!shouldRollBack) {
            return globalSession.getStatus();
        }

        doGlobalRollback(globalSession, false);
        return globalSession.getStatus();
    }

    @Override
    public boolean doGlobalRollback(GlobalSession globalSession, boolean retrying) throws TransactionException {
        if (RaftServerFactory.getInstance().getRaftServer() != null) {
            return doRaftGlobalRollback(globalSession, retrying);
        } else {
            boolean success = true;
            // start rollback event
            eventBus.post(new GlobalTransactionEvent(globalSession.getTransactionId(), GlobalTransactionEvent.ROLE_TC,
                globalSession.getTransactionName(), globalSession.getBeginTime(), null, globalSession.getStatus()));

            if (globalSession.isSaga()) {
                success = getCore(BranchType.SAGA).doGlobalRollback(globalSession, retrying);
            } else {
                for (BranchSession branchSession : globalSession.getReverseSortedBranches()) {
                    BranchStatus currentBranchStatus = branchSession.getStatus();
                    if (currentBranchStatus == BranchStatus.PhaseOne_Failed) {
                        globalSession.removeBranch(branchSession);
                        continue;
                    }
                    try {
                        BranchStatus branchStatus = branchRollback(globalSession, branchSession);
                        switch (branchStatus) {
                            case PhaseTwo_Rollbacked:
                                globalSession.removeBranch(branchSession);
                                LOGGER.info("Rollback branch transaction successfully, xid = {} branchId = {}",
                                    globalSession.getXid(), branchSession.getBranchId());
                                continue;
                            case PhaseTwo_RollbackFailed_Unretryable:
                                SessionHelper.endRollbackFailed(globalSession);
                                LOGGER.info("Rollback branch transaction fail and stop retry, xid = {} branchId = {}",
                                    globalSession.getXid(), branchSession.getBranchId());
                                return false;
                            default:
                                LOGGER.info("Rollback branch transaction fail and will retry, xid = {} branchId = {}",
                                    globalSession.getXid(), branchSession.getBranchId());
                                if (!retrying) {
                                    globalSession.queueToRetryRollback();
                                }
                                return false;
                        }
                    } catch (Exception ex) {
                        StackTraceLogger.error(LOGGER, ex,
                            "Rollback branch transaction exception, xid = {} branchId = {} exception = {}",
                            new String[] {globalSession.getXid(), String.valueOf(branchSession.getBranchId()), ex.getMessage()});
                        if (!retrying) {
                            globalSession.queueToRetryRollback();
                        }
                        throw new TransactionException(ex);
                    }
                }

                // In db mode, there is a problem of inconsistent data in multiple copies, resulting in new branch
                // transaction registration when rolling back.
                // 1. New branch transaction and rollback branch transaction have no data association
                // 2. New branch transaction has data association with rollback branch transaction
                // The second query can solve the first problem, and if it is the second problem, it may cause a rollback
                // failure due to data changes.
                GlobalSession globalSessionTwice = SessionHolder.findGlobalSession(globalSession.getXid());
                if (globalSessionTwice != null && globalSessionTwice.hasBranch()) {
                    LOGGER.info("Rollbacking global transaction is NOT done, xid = {}.", globalSession.getXid());
                    return false;
                }
            }
            if (success) {
                SessionHelper.endRollbacked(globalSession);

                // rollbacked event
                eventBus.post(
                    new GlobalTransactionEvent(globalSession.getTransactionId(), GlobalTransactionEvent.ROLE_TC,
                        globalSession.getTransactionName(), globalSession.getBeginTime(), System.currentTimeMillis(),
                        globalSession.getStatus()));

                LOGGER.info("Rollback global transaction successfully, xid = {}.", globalSession.getXid());
            }
            return success;
        }
    }

    @Override
    public GlobalStatus getStatus(String xid) throws TransactionException {
        GlobalSession globalSession = SessionHolder.findGlobalSession(xid, false);
        if (globalSession == null) {
            return GlobalStatus.Finished;
        } else {
            return globalSession.getStatus();
        }
    }

    @Override
    public GlobalStatus globalReport(String xid, GlobalStatus globalStatus) throws TransactionException {
        GlobalSession globalSession = SessionHolder.findGlobalSession(xid);
        if (globalSession == null) {
            return globalStatus;
        }
        globalSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
        doGlobalReport(globalSession, xid, globalStatus);
        return globalSession.getStatus();
    }

    @Override
    public void doGlobalReport(GlobalSession globalSession, String xid, GlobalStatus globalStatus) throws TransactionException {
        if (globalSession.isSaga()) {
            getCore(BranchType.SAGA).doGlobalReport(globalSession, xid, globalStatus);
        }
    }

    public boolean doRaftGlobalCommit(GlobalSession globalSession, boolean retrying) throws TransactionException {
        boolean success = true;
        Boolean isRaftMode = RaftServerFactory.getInstance().isRaftMode();
        // start committing event
        eventBus.post(new GlobalTransactionEvent(globalSession.getTransactionId(), GlobalTransactionEvent.ROLE_TC,
            globalSession.getTransactionName(), globalSession.getBeginTime(), null, globalSession.getStatus()));

        if (globalSession.isSaga()) {
            throw new TransactionException("saga is not supported for the time being");
        } else {
            List<BranchSession> sessionList = globalSession.getSortedBranches();
            Map<Long, BranchStatus> branchStatusMap = new HashMap<>();
            for (BranchSession branchSession : sessionList) {
                // if not retrying, skip the canBeCommittedAsync branches
                if (!retrying && branchSession.canBeCommittedAsync()) {
                    continue;
                }

                BranchStatus currentStatus = branchSession.getStatus();
                if (currentStatus == BranchStatus.PhaseOne_Failed) {
                    globalSession.removeBranch(branchSession);
                    continue;
                }
                try {
                    BranchStatus branchStatus =
                        getCore(branchSession.getBranchType()).branchCommit(globalSession, branchSession);
                    if (!retrying && isRaftMode) {
                        branchStatusMap.put(branchSession.getBranchId(), branchStatus);
                    } else {
                        switch (branchStatus) {
                            case PhaseTwo_Committed:
                                globalSession.removeBranch(branchSession);
                                continue;
                            case PhaseTwo_CommitFailed_Unretryable:
                                if (globalSession.canBeCommittedAsync()) {
                                    LOGGER.error(
                                        "Committing branch transaction[{}], status: PhaseTwo_CommitFailed_Unretryable, please check the business log.",
                                        branchSession.getBranchId());
                                    continue;
                                } else {
                                    SessionHelper.endCommitFailed(globalSession);
                                    LOGGER.error(
                                        "Committing global transaction[{}] finally failed, caused by branch transaction[{}] commit failed.",
                                        globalSession.getXid(), branchSession.getBranchId());
                                    return false;
                                }
                            default:
                                if (!retrying) {
                                    globalSession.queueToRetryCommit();
                                    return false;
                                }
                                if (globalSession.canBeCommittedAsync()) {
                                    LOGGER.error("Committing branch transaction[{}], status:{} and will retry later",
                                        branchSession.getBranchId(), branchStatus);
                                    continue;
                                } else {
                                    LOGGER.error(
                                        "Committing global transaction[{}] failed, caused by branch transaction[{}] commit failed, will retry later.",
                                        globalSession.getXid(), branchSession.getBranchId());
                                    return false;
                                }
                        }
                    }
                } catch (Exception ex) {
                    StackTraceLogger.error(LOGGER, ex, "Committing branch transaction exception: {}",
                        new String[] {branchSession.toString()});
                    if (!retrying) {
                        globalSession.queueToRetryCommit();
                        throw new TransactionException(ex);
                    }
                }
            }
            if (!branchStatusMap.isEmpty()) {
                RaftCoreClosure closure = new RaftCoreClosure() {
                    Map<Long, BranchStatus> branchStatusMap;
                    String xid;

                    @Override
                    public void setBranchStatusMap(Map<Long, BranchStatus> branchStatusMap) {
                        this.branchStatusMap = branchStatusMap;
                    }

                    @Override
                    public void setXid(String xid) {
                        this.xid = xid;
                    }

                    @Override
                    public void run(Status status) {
                        doCommit(branchStatusMap, xid, true);
                    }
                };
                closure.setBranchStatusMap(branchStatusMap);
                closure.setXid(globalSession.getXid());
                final Task task = new Task();
                RaftCoreSyncMsg msg = new RaftCoreSyncMsg(branchStatusMap, globalSession.getXid());
                msg.setMsgType(DO_COMMIT);
                try {
                    task.setData(ByteBuffer.wrap(SerializerManager.getSerializer(Hessian2).serialize(msg)));
                } catch (CodecException e) {
                    e.printStackTrace();
                }
                task.setDone(closure);
                RaftServerFactory.getInstance().getRaftServer().getNode().apply(task);
            }
            if (!isRaftMode && globalSession.hasBranch()) {
                LOGGER.info("Committing global transaction is NOT done, xid = {}.", globalSession.getXid());
                return false;
            }
        }
        if (success && globalSession.getBranchSessions().isEmpty()) {
            if (retrying || !isRaftMode) {
                SessionHelper.endCommitted(globalSession);

                // committed event
                eventBus.post(new GlobalTransactionEvent(globalSession.getTransactionId(),
                    GlobalTransactionEvent.ROLE_TC, globalSession.getTransactionName(), globalSession.getBeginTime(),
                    System.currentTimeMillis(), globalSession.getStatus()));

                LOGGER.info("Committing global transaction is successfully done, xid = {}.", globalSession.getXid());
            }
        }
        return success;
    }

    public boolean doRaftGlobalRollback(GlobalSession globalSession, boolean retrying) throws TransactionException {
        Boolean isRaftMode = RaftServerFactory.getInstance().isRaftMode();
        if (isRaftMode) {
            if (!RaftServerFactory.getInstance().isLeader()) {
                // is not a leader, will not really respond to the client
                return true;
            }
        }
        boolean success = true;
        // start rollback event
        eventBus.post(new GlobalTransactionEvent(globalSession.getTransactionId(), GlobalTransactionEvent.ROLE_TC,
            globalSession.getTransactionName(), globalSession.getBeginTime(), null, globalSession.getStatus()));

        if (globalSession.isSaga()) {
            throw new TransactionException("saga is not supported for the time being");
        } else {
            List<BranchSession> sessionList = globalSession.getReverseSortedBranches();
            Map<Long, BranchStatus> branchStatusMap = new HashMap<>();
            for (BranchSession branchSession : sessionList) {
                BranchStatus currentBranchStatus = branchSession.getStatus();
                if (currentBranchStatus == BranchStatus.PhaseOne_Failed) {
                    globalSession.removeBranch(branchSession);
                    continue;
                }
                try {
                    BranchStatus branchStatus = branchRollback(globalSession, branchSession);
                    if (isRaftMode && !retrying) {
                        branchStatusMap.put(branchSession.getBranchId(), branchStatus);
                    } else {
                        switch (branchStatus) {
                            case PhaseTwo_Rollbacked:
                                globalSession.removeBranch(branchSession);
                                LOGGER.info("Rollback branch transaction successfully, xid = {} branchId = {}",
                                    globalSession.getXid(), branchSession.getBranchId());
                                continue;
                            case PhaseTwo_RollbackFailed_Unretryable:
                                SessionHelper.endRollbackFailed(globalSession);
                                LOGGER.info("Rollback branch transaction fail and stop retry, xid = {} branchId = {}",
                                    globalSession.getXid(), branchSession.getBranchId());
                                return false;
                            default:
                                LOGGER.info("Rollback branch transaction fail and will retry, xid = {} branchId = {}",
                                    globalSession.getXid(), branchSession.getBranchId());
                                if (!retrying) {
                                    globalSession.queueToRetryRollback();
                                }
                                return false;
                        }
                    }
                } catch (Exception ex) {
                    StackTraceLogger.error(LOGGER, ex,
                        "Rollback branch transaction exception, xid = {} branchId = {} exception = {}", new String[] {
                            globalSession.getXid(), String.valueOf(branchSession.getBranchId()), ex.getMessage()});
                    if (!retrying) {
                        globalSession.queueToRetryRollback();
                    }
                    throw new TransactionException(ex);
                }

            }
            if (!branchStatusMap.isEmpty()) {
                RaftCoreClosure closure = new RaftCoreClosure() {
                    Map<Long, BranchStatus> branchStatusMap;
                    String xid;

                    @Override
                    public void setBranchStatusMap(Map<Long, BranchStatus> branchStatusMap) {
                        this.branchStatusMap = branchStatusMap;
                    }

                    @Override
                    public void setXid(String xid) {
                        this.xid = xid;
                    }

                    @Override
                    public void run(Status status) {
                        doRollback(branchStatusMap, xid, true);
                    }
                };
                closure.setBranchStatusMap(branchStatusMap);
                closure.setXid(globalSession.getXid());
                final Task task = new Task();
                RaftCoreSyncMsg msg = new RaftCoreSyncMsg(branchStatusMap, globalSession.getXid());
                msg.setMsgType(DO_ROLLBACK);
                try {
                    task.setData(ByteBuffer.wrap(SerializerManager.getSerializer(Hessian2).serialize(msg)));
                } catch (CodecException e) {
                    e.printStackTrace();
                }
                task.setDone(closure);
                RaftServerFactory.getInstance().getRaftServer().getNode().apply(task);
            }
            // In db mode, there is a problem of inconsistent data in multiple copies, resulting in new branch
            // transaction registration when rolling back.
            // 1. New branch transaction and rollback branch transaction have no data association
            // 2. New branch transaction has data association with rollback branch transaction
            // The second query can solve the first problem, and if it is the second problem, it may cause a rollback
            // failure due to data changes.
            GlobalSession globalSessionTwice = SessionHolder.findGlobalSession(globalSession.getXid());
            if (!isRaftMode && globalSessionTwice != null && globalSessionTwice.hasBranch()) {
                LOGGER.info("Rollbacking global transaction is NOT done, xid = {}.", globalSession.getXid());
                return false;
            }
        }
        if (success) {
            if(!isRaftMode) {
                SessionHelper.endRollbacked(globalSession);

                // rollbacked event
                eventBus.post(new GlobalTransactionEvent(globalSession.getTransactionId(), GlobalTransactionEvent.ROLE_TC,
                    globalSession.getTransactionName(), globalSession.getBeginTime(), System.currentTimeMillis(),
                    globalSession.getStatus()));

                LOGGER.info("Rollback global transaction successfully, xid = {}.", globalSession.getXid());
            }
        }
        return success;
    }

    public void doRollback(Map<Long, BranchStatus> branchStatusMap, String xid, boolean leader) {
        GlobalSession globalSession = SessionHolder.getRootSessionManager().findGlobalSession(xid);
        if (globalSession == null) {
            return;
        }
        for (Map.Entry<Long, BranchStatus> entry : branchStatusMap.entrySet()) {
            BranchSession branchSession = globalSession.getBranch(entry.getKey());
            if (branchSession != null) {
                BranchStatus branchStatus = entry.getValue();
                try {
                    switch (branchStatus) {
                        case PhaseTwo_Rollbacked:
                            globalSession.removeBranch(branchSession);
                            LOGGER.info("Rollback branch transaction successfully, xid = {} branchId = {}",
                                globalSession.getXid(), branchSession.getBranchId());
                            continue;
                        case PhaseTwo_RollbackFailed_Unretryable:
                            SessionHelper.endRollbackFailed(globalSession);
                            LOGGER.info("Rollback branch transaction fail and stop retry, xid = {} branchId = {}",
                                globalSession.getXid(), branchSession.getBranchId());
                            return;
                        default:
                            LOGGER.info("Rollback branch transaction fail and will retry, xid = {} branchId = {}",
                                globalSession.getXid(), branchSession.getBranchId());
                            globalSession.queueToRetryRollback();
                            return;
                    }
                } catch (Exception ex) {
                    StackTraceLogger.error(LOGGER, ex,
                        "Rollback branch transaction exception, xid = {} branchId = {} exception = {}", new String[] {
                            globalSession.getXid(), String.valueOf(branchSession.getBranchId()), ex.getMessage()});
                    if (leader) {
                        try {
                            globalSession.queueToRetryCommit();
                        } catch (TransactionException e) {
                            ex.printStackTrace();
                        }
                    }
                }
            }
        }
        if (!globalSession.hasBranch()) {
            try {
                SessionHelper.endRollbacked(globalSession);
            } catch (TransactionException e) {
                e.printStackTrace();
            }
            // rollbacked event
            eventBus.post(new GlobalTransactionEvent(globalSession.getTransactionId(), GlobalTransactionEvent.ROLE_TC,
                globalSession.getTransactionName(), globalSession.getBeginTime(), System.currentTimeMillis(),
                globalSession.getStatus()));

            LOGGER.info("Rollback global transaction successfully, xid = {}.", globalSession.getXid());
        }
    }

    public void doCommit(Map<Long, BranchStatus> branchStatusMap, String xid, boolean leader) {
        GlobalSession globalSession = SessionHolder.getRootSessionManager().findGlobalSession(xid);
        for (Map.Entry<Long, BranchStatus> entry : branchStatusMap.entrySet()) {
            BranchSession branchSession = globalSession.getBranch(entry.getKey());
            BranchStatus branchStatus = entry.getValue();
            try {
                switch (branchStatus) {
                    case PhaseTwo_Committed:
                        globalSession.removeBranch(branchSession);
                        continue;
                    case PhaseTwo_CommitFailed_Unretryable:
                        if (globalSession.canBeCommittedAsync()) {
                            LOGGER.error(
                                "Committing branch transaction[{}], status: PhaseTwo_CommitFailed_Unretryable, please check the business log.",
                                branchSession.getBranchId());
                            continue;
                        } else {
                            SessionHelper.endCommitFailed(globalSession);
                            LOGGER.error(
                                "Committing global transaction[{}] finally failed, caused by branch transaction[{}] commit failed.",
                                globalSession.getXid(), branchSession.getBranchId());
                            return;
                        }
                    default:
                        globalSession.queueToRetryCommit();
                        return;
                }
            } catch (Exception e) {
                StackTraceLogger.error(LOGGER, e, "Committing branch transaction exception: {}",
                    new String[] {branchSession.toString()});
                if (leader) {
                    try {
                        globalSession.queueToRetryCommit();
                    } catch (TransactionException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
        if (globalSession.getBranchSessions().isEmpty()) {
            try {
                SessionHelper.endCommitted(globalSession);
            } catch (TransactionException e) {
                e.printStackTrace();
            }

            // committed event
            eventBus.post(new GlobalTransactionEvent(globalSession.getTransactionId(), GlobalTransactionEvent.ROLE_TC,
                globalSession.getTransactionName(), globalSession.getBeginTime(), System.currentTimeMillis(),
                globalSession.getStatus()));

            LOGGER.info("Committing global transaction is successfully done, xid = {}.", globalSession.getXid());
        }
    }

}

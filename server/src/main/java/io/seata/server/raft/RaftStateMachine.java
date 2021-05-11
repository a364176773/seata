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
package io.seata.server.raft;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.alipay.remoting.serialization.SerializerManager;
import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.error.RaftError;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotReader;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotWriter;
import com.alipay.sofa.jraft.util.Utils;
import io.seata.common.util.StringUtils;
import io.seata.config.ConfigurationFactory;
import io.seata.core.constants.ConfigurationKeys;
import io.seata.core.exception.TransactionException;
import io.seata.core.model.GlobalStatus;
import io.seata.core.raft.AbstractRaftStateMachine;
import io.seata.core.raft.RaftServerFactory;
import io.seata.core.store.StoreMode;
import io.seata.server.raft.execute.RaftMsgExecute;
import io.seata.server.raft.execute.branch.AddBranchSessionExecute;
import io.seata.server.raft.execute.branch.RemoveBranchSessionExecute;
import io.seata.server.raft.execute.branch.UpdateBranchSessionExecute;
import io.seata.server.raft.execute.global.AddGlobalSessionExecute;
import io.seata.server.raft.execute.global.RemoveGlobalSessionExecute;
import io.seata.server.raft.execute.global.UpdateGlobalSessionExecute;
import io.seata.server.raft.execute.lock.AcquireLockExecute;
import io.seata.server.raft.execute.lock.ReleaseLockExecute;
import io.seata.server.session.BranchSession;
import io.seata.server.session.GlobalSession;
import io.seata.server.session.SessionHolder;
import io.seata.server.session.SessionManager;
import io.seata.server.storage.raft.RaftSessionSyncMsg;
import io.seata.server.storage.raft.session.RaftSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import static com.alipay.remoting.serialization.SerializerManager.Hessian2;
import static io.seata.core.raft.msg.RaftSyncMsg.MsgType;
import static io.seata.core.raft.msg.RaftSyncMsg.MsgType.ACQUIRE_LOCK;
import static io.seata.core.raft.msg.RaftSyncMsg.MsgType.ADD_BRANCH_SESSION;
import static io.seata.core.raft.msg.RaftSyncMsg.MsgType.ADD_GLOBAL_SESSION;
import static io.seata.core.raft.msg.RaftSyncMsg.MsgType.RELEASE_GLOBAL_SESSION_LOCK;
import static io.seata.core.raft.msg.RaftSyncMsg.MsgType.REMOVE_BRANCH_SESSION;
import static io.seata.core.raft.msg.RaftSyncMsg.MsgType.REMOVE_GLOBAL_SESSION;
import static io.seata.core.raft.msg.RaftSyncMsg.MsgType.UPDATE_BRANCH_SESSION_STATUS;
import static io.seata.core.raft.msg.RaftSyncMsg.MsgType.UPDATE_GLOBAL_SESSION_STATUS;
import static io.seata.server.session.SessionHolder.ASYNC_COMMITTING_SESSION_MANAGER_NAME;
import static io.seata.server.session.SessionHolder.RETRY_COMMITTING_SESSION_MANAGER_NAME;
import static io.seata.server.session.SessionHolder.RETRY_ROLLBACKING_SESSION_MANAGER_NAME;
import static io.seata.server.session.SessionHolder.ROOT_SESSION_MANAGER_NAME;

/**
 * @author funkye
 */
public class RaftStateMachine extends AbstractRaftStateMachine {

    private static final Logger LOGGER = LoggerFactory.getLogger(RaftStateMachine.class);

    private String mode;

    private static final String BRANCH_SESSION_MAP = "branchSessionMap";

    public RaftStateMachine() {
        mode = ConfigurationFactory.getInstance().getConfig(ConfigurationKeys.STORE_MODE);
    }

    @Override
    public void onApply(Iterator iterator) {
        while (iterator.hasNext()) {
            Closure closure = null;
            if (iterator.done() != null) {
                closure = iterator.done();
            } else {
                try {
                    ByteBuffer byteBuffer = iterator.getData();
                    if (byteBuffer != null) {
                        RaftSessionSyncMsg msg = SerializerManager.getSerializer(Hessian2)
                            .deserialize(iterator.getData().array(), RaftSessionSyncMsg.class.getName());
                        onExecuteRaft(msg);
                    }
                } catch (Throwable e) {
                    LOGGER.error("Message synchronization failure", e);
                }
            }
            Optional.ofNullable(closure).ifPresent(processor -> processor.run(Status.OK()));
            iterator.next();
        }
    }

    @Override
    public void onSnapshotSave(final SnapshotWriter writer, final Closure done) {
        if (!StringUtils.equals(StoreMode.RAFT.getName(), mode)) {
            return;
        }
        // gets a record of the lock and session at the moment
        Map<String, Object> maps = new HashMap<>();
        RaftSessionManager raftSessionManager = (RaftSessionManager)SessionHolder.getRootSessionManager();
        Map<String, GlobalSession> sessionMap = raftSessionManager.getSessionMap();
        Map<String, byte[]> globalSessionByteMap = new HashMap<>();
        Map<Long, byte[]> branchSessionByteMap = new HashMap<>();
        sessionMap.forEach((k, v) -> {
            globalSessionByteMap.put(v.getXid(), v.encode());
            List<BranchSession> branchSessions = v.getBranchSessions();
            branchSessions.forEach(
                branchSession -> branchSessionByteMap.put(branchSession.getBranchId(), branchSession.encode()));
        });
        maps.put(ROOT_SESSION_MANAGER_NAME, globalSessionByteMap);
        maps.put(BRANCH_SESSION_MAP, branchSessionByteMap);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("globalSessionmap size :{}, branchSessionMap map size: {}", globalSessionByteMap.size(),
                branchSessionByteMap.size());
        }
        if (maps.isEmpty()) {
            return;
        }
        // async save
        Utils.runInThread(() -> {
            final RaftSnapshotFile snapshot = new RaftSnapshotFile(writer.getPath() + File.separator + "data");
            if (snapshot.save(maps)) {
                if (writer.addFile("data")) {
                    done.run(Status.OK());
                } else {
                    done.run(new Status(RaftError.EIO, "Fail to add file to writer"));
                }
            } else {
                done.run(new Status(RaftError.EIO, "Fail to save counter snapshot %s", snapshot.getPath()));
            }
        });
    }

    @Override
    public boolean onSnapshotLoad(final SnapshotReader reader) {
        if (!StringUtils.equals(StoreMode.RAFT.getName(), mode)) {
            return false;
        }
        if (isLeader()) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Leader is not supposed to load snapshot");
            }
            return false;
        }
        if (reader.getFileMeta("data") == null) {
            LOGGER.error("Fail to find data file in {}", reader.getPath());
            return false;
        }
        final RaftSnapshotFile snapshot = new RaftSnapshotFile(reader.getPath() + File.separator + "data");
        try {
            Map<String, Object> maps = snapshot.load();
            RaftSessionManager raftSessionManager = (RaftSessionManager)SessionHolder.getRootSessionManager();
            Map<String, byte[]> globalSessionByteMap = (Map<String, byte[]>)maps.get(ROOT_SESSION_MANAGER_NAME);
            Map<String, byte[]> branchSessionByteMap = (Map<String, byte[]>)maps.get(BRANCH_SESSION_MAP);
            Map<String, GlobalSession> rootSessionMap = raftSessionManager.getSessionMap();
            if (!globalSessionByteMap.isEmpty()) {
                Map<String, GlobalSession> sessionMap = new HashMap<>();
                globalSessionByteMap.forEach((k, v) -> {
                    GlobalSession session = new GlobalSession();
                    session.decode(v);
                    sessionMap.put(k, session);
                });
                rootSessionMap.putAll(sessionMap);
                branchSessionByteMap.forEach((k, v) -> {
                    BranchSession branchSession = new BranchSession();
                    branchSession.decode(v);
                    rootSessionMap.computeIfPresent(branchSession.getXid(), (key, globalSession) -> {
                        globalSession.getBranchSessions().add(branchSession);
                        try {
                            branchSession.lock();
                        } catch (TransactionException e) {
                            LOGGER.error("failed to restore branch transaction: {} lock record: {}",
                                branchSession.getBranchId(), branchSession.getLockKey());
                        }
                        return globalSession;
                    });
                });
                sessionMap.forEach((k, v) -> {
                    GlobalStatus status = v.getStatus();
                    try {
                        if (status == GlobalStatus.AsyncCommitting) {
                            SessionHolder.getAsyncCommittingSessionManager().addGlobalSession(v);
                        } else if (status == GlobalStatus.CommitRetrying) {
                            SessionHolder.getRetryCommittingSessionManager().addGlobalSession(v);
                        } else if (status == GlobalStatus.RollbackRetrying) {
                            SessionHolder.getRetryRollbackingSessionManager().addGlobalSession(v);
                        }
                    } catch (TransactionException e) {
                        LOGGER.error("fail to load global session from {},error:{}", v.getXid(), e.getMessage(), e);
                    }
                });
            }
            return true;
        } catch (final Exception e) {
            LOGGER.error("fail to load snapshot from {}", snapshot.getPath());
            return false;
        }

    }

    @Override
    public void onLeaderStart(final long term) {
        // become the leader again,reloading global session
        if (!isLeader() && RaftServerFactory.getInstance().isRaftMode()) {
            RaftSessionManager raftSessionManager = (RaftSessionManager)SessionHolder.getRootSessionManager();
            Map<String, GlobalSession> retryRollbackingMap =
                ((RaftSessionManager)SessionHolder.getRetryRollbackingSessionManager()).getSessionMap();
            Map<String, GlobalSession> sessionMap = raftSessionManager.getSessionMap();
            sessionMap.forEach((k, v) -> {
                GlobalStatus status = v.getStatus();
                if (status == GlobalStatus.RollbackRetrying || status == GlobalStatus.Rollbacking
                    || status == GlobalStatus.TimeoutRollbacking || status == GlobalStatus.TimeoutRollbackRetrying) {
                    retryRollbackingMap.computeIfAbsent(v.getXid(), session -> {
                        v.addSessionLifecycleListener(SessionHolder.getRetryRollbackingSessionManager());
                        return v;
                    });
                }
            });
        }
        this.leaderTerm.set(term);
        super.onLeaderStart(term);
    }

    @Override
    public void onLeaderStop(final Status status) {
        this.leaderTerm.set(-1);
        super.onLeaderStop(status);
    }

    private void onExecuteRaft(RaftSessionSyncMsg msg) throws Throwable {
        MsgType msgType = msg.getMsgType();
        SessionManager sessionManager = null;
        String sessionName = msg.getSessionName();
        Boolean rootManager = false;
        if (StringUtils.equalsIgnoreCase(sessionName, ROOT_SESSION_MANAGER_NAME)) {
            sessionManager = SessionHolder.getRootSessionManager();
            rootManager = true;
        } else if (StringUtils.equalsIgnoreCase(sessionName, ASYNC_COMMITTING_SESSION_MANAGER_NAME)) {
            sessionManager = SessionHolder.getAsyncCommittingSessionManager();
        } else if (StringUtils.equalsIgnoreCase(sessionName, RETRY_COMMITTING_SESSION_MANAGER_NAME)) {
            sessionManager = SessionHolder.getRetryCommittingSessionManager();
        } else if (StringUtils.equalsIgnoreCase(sessionName, RETRY_ROLLBACKING_SESSION_MANAGER_NAME)) {
            sessionManager = SessionHolder.getRetryRollbackingSessionManager();
        }
        RaftSessionManager raftSessionManager = sessionManager != null ? (RaftSessionManager)sessionManager : null;
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("state machine synchronization,task:{},sessionManager:{}", msgType,
                sessionName != null ? sessionName : ROOT_SESSION_MANAGER_NAME);
        }
        RaftMsgExecute execute = null;
        if (ADD_GLOBAL_SESSION.equals(msgType)) {
            execute = new AddGlobalSessionExecute(msg, raftSessionManager, rootManager);
        } else if (ACQUIRE_LOCK.equals(msgType)) {
            execute = new AcquireLockExecute(msg, raftSessionManager);
        } else if (ADD_BRANCH_SESSION.equals(msgType)) {
            execute = new AddBranchSessionExecute(msg, raftSessionManager);
        } else if (UPDATE_GLOBAL_SESSION_STATUS.equals(msgType)) {
            execute = new UpdateGlobalSessionExecute(msg, raftSessionManager);
        } else if (REMOVE_BRANCH_SESSION.equals(msgType)) {
            execute = new RemoveBranchSessionExecute(msg, raftSessionManager);
        } else if (RELEASE_GLOBAL_SESSION_LOCK.equals(msgType)) {
            execute = new ReleaseLockExecute(msg, raftSessionManager);
        } else if (REMOVE_GLOBAL_SESSION.equals(msgType)) {
            execute = new RemoveGlobalSessionExecute(msg, raftSessionManager, rootManager);
        } else if (UPDATE_BRANCH_SESSION_STATUS.equals(msgType)) {
            execute = new UpdateBranchSessionExecute(msg, raftSessionManager);
        }

        execute.execute();

    }
}

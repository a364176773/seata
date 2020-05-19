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
package io.seata.server.storage.redis.store;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.alibaba.fastjson.JSON;

import io.seata.common.exception.StoreException;
import io.seata.common.util.CollectionUtils;
import io.seata.common.util.StringUtils;
import io.seata.config.ConfigurationFactory;
import io.seata.core.constants.ConfigurationKeys;
import io.seata.core.model.BranchStatus;
import io.seata.core.model.BranchType;
import io.seata.core.model.GlobalStatus;
import io.seata.core.store.BranchTransactionDO;
import io.seata.core.store.GlobalTransactionDO;
import io.seata.server.session.BranchSession;
import io.seata.server.session.GlobalSession;
import io.seata.server.session.SessionCondition;
import io.seata.server.storage.redis.JedisPooledFactory;
import io.seata.server.store.AbstractTransactionStoreManager;
import io.seata.server.store.SessionStorable;
import io.seata.server.store.TransactionStoreManager;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;

/**
 * @author funkye
 */
public class RedisTransactionStoreManager extends AbstractTransactionStoreManager implements TransactionStoreManager {

    // global transaction prefix
    private static final String DEFAULT_REDIS_SEATA_GLOBAL_PREFIX = "SEATA_GLOBAL_";

    // the prefix of the branchs transaction
    private static final String DEFAULT_REDIS_SEATA_XID_BRANCHS_PREFIX = "SEATA_XID_BRANCHS_";

    // the prefix of the branch transaction
    private static final String DEFAULT_REDIS_SEATA_BRANCH_PREFIX = "SEATA_BRANCH_";

    // global transaction id PREFIX
    private static final String DEFAULT_SEATA_TRANSACTION_ID_GLOBAL_PREFIX = "SEATA_TRANSACTION_ID_GLOBAL_";

    private static volatile RedisTransactionStoreManager instance;

    /**
     * The query limit.
     */
    private int logQueryLimit;

    public RedisTransactionStoreManager() {
        logQueryLimit = ConfigurationFactory.getInstance().getInt(ConfigurationKeys.STORE_REDIS_QUERY_LIMIT, 100);
    }

    /**
     * Get the instance.
     */
    public static RedisTransactionStoreManager getInstance() {
        if (null == instance) {
            synchronized (RedisTransactionStoreManager.class) {
                if (null == instance) {
                    instance = new RedisTransactionStoreManager();
                }
            }
        }
        return instance;
    }

    @Override
    public boolean writeSession(LogOperation logOperation, SessionStorable session) {
        if (LogOperation.GLOBAL_ADD.equals(logOperation)) {
            return insertOrUpdateGlobalTransactionDO(convertGlobalTransactionDO(session));
        } else if (LogOperation.GLOBAL_UPDATE.equals(logOperation)) {
            return insertOrUpdateGlobalTransactionDO(convertGlobalTransactionDO(session));
        } else if (LogOperation.GLOBAL_REMOVE.equals(logOperation)) {
            return deleteGlobalTransactionDO(convertGlobalTransactionDO(session));
        } else if (LogOperation.BRANCH_ADD.equals(logOperation)) {
            return insertOrUpdateBranchTransactionDO(convertBranchTransactionDO(session));
        } else if (LogOperation.BRANCH_UPDATE.equals(logOperation)) {
            return insertOrUpdateBranchTransactionDO(convertBranchTransactionDO(session));
        } else if (LogOperation.BRANCH_REMOVE.equals(logOperation)) {
            return deleteBranchTransactionDO(convertBranchTransactionDO(session));
        } else {
            throw new StoreException("Unknown LogOperation:" + logOperation.name());
        }
    }

    private boolean deleteBranchTransactionDO(BranchTransactionDO convertBranchTransactionDO) {
        try (Jedis jedis = JedisPooledFactory.getJedisInstance()) {
            String branchesKey = getBranchListKeyByXid(convertBranchTransactionDO.getXid());
            Set<String> keys = range(jedis, branchesKey);
            if (null != keys && keys.size() > 0) {
                String key = getBranchKey(convertBranchTransactionDO.getBranchId());
                Iterator<String> it = keys.iterator();
                while (it.hasNext()) {
                    String s = it.next();
                    if (Objects.equals(s, key)) {
                        it.remove();
                        jedis.del(key);
                        jedis.lrem(branchesKey, 0, key);
                        break;
                    }
                }
                if (keys.size() == 0) {
                    jedis.del(branchesKey);
                }
            }
            return true;
        }
    }

    private boolean insertOrUpdateBranchTransactionDO(BranchTransactionDO convertBranchTransactionDO) {
        try (Jedis jedis = JedisPooledFactory.getJedisInstance()) {
            String key = getBranchKey(convertBranchTransactionDO.getBranchId());
            if (jedis.get(key) == null) {
                jedis.lpush(getBranchListKeyByXid(convertBranchTransactionDO.getXid()), key);
            }
            jedis.set(key, JSON.toJSONString(convertBranchTransactionDO));
            return true;
        }
    }

    private boolean deleteGlobalTransactionDO(GlobalTransactionDO convertGlobalTransactionDO) {
        try (Jedis jedis = JedisPooledFactory.getJedisInstance()) {
            String[] keys = new String[3];
            keys[0] = getGlobalKeyByXid(convertGlobalTransactionDO.getXid());
            keys[1] = getGlobalKeyByTransactionId(convertGlobalTransactionDO.getTransactionId());
            String branchesKey = getBranchListKeyByXid(convertGlobalTransactionDO.getXid());
            List<String> redisBranches = jedis.lrange(branchesKey, 0, 1);
            if (null == redisBranches || redisBranches.size() <= 0) {
                keys[2] = branchesKey;
            }
            jedis.del(keys);
            return true;
        }
    }

    private boolean insertOrUpdateGlobalTransactionDO(GlobalTransactionDO convertGlobalTransactionDO) {
        try (Jedis jedis = JedisPooledFactory.getJedisInstance()) {
            String keys = getGlobalKeyByXid(convertGlobalTransactionDO.getXid());
            jedis.set(keys, JSON.toJSONString(convertGlobalTransactionDO));
            keys = getGlobalKeyByTransactionId(convertGlobalTransactionDO.getTransactionId());
            jedis.set(keys, JSON.toJSONString(convertGlobalTransactionDO));
            return true;
        }
    }

    /**
     * Read session global session.
     *
     * @param xid
     *            the xid
     * @param withBranchSessions
     *            the withBranchSessions
     * @return the global session
     */
    @Override
    public GlobalSession readSession(String xid, boolean withBranchSessions) {
        String globalSessionJson;
        try (Jedis jedis = JedisPooledFactory.getJedisInstance()) {
            globalSessionJson = jedis.get(getGlobalKeyByXid(xid));
            if (StringUtils.isBlank(globalSessionJson)) {
                return null;
            }
            // global transaction
            GlobalTransactionDO globalTransactionDO = JSON.parseObject(globalSessionJson, GlobalTransactionDO.class);
            if (globalTransactionDO == null) {
                return null;
            }
            // branch transactions
            List<BranchTransactionDO> branchTransactionDOs = null;
            // reduce rpc with db when branchRegister and getGlobalStatus
            if (withBranchSessions) {
                Set<String> keys = range(jedis, getBranchListKeyByXid(globalTransactionDO.getXid()));
                if (null != keys && keys.size() > 0) {
                    branchTransactionDOs = new ArrayList<>();
                    for (String s : keys) {
                        branchTransactionDOs.add(JSON.parseObject(jedis.get(s), BranchTransactionDO.class));
                    }
                }
            }
            return getGlobalSession(globalTransactionDO, branchTransactionDOs);
        }
    }

    /**
     * Read session global session.
     *
     * @param xid
     *            the xid
     * @return the global session
     */
    @Override
    public GlobalSession readSession(String xid) {
        return this.readSession(xid, true);
    }

    /**
     * Read session list.
     *
     * @param statuses
     *            the statuses
     * @return the list
     */
    public List<GlobalSession> readSession(GlobalStatus[] statuses) {
        List<Integer> states = new ArrayList<>();
        for (int i = 0; i < statuses.length; i++) {
            states.add(statuses[i].getCode());
        }
        try (Jedis jedis = JedisPooledFactory.getJedisInstance()) {
            Set<String> keys = new HashSet<>();
            String cursor = "0";
            ScanParams params = new ScanParams();
            params.count(100);
            params.match(getGlobalKeyByXid("*"));
            ScanResult<String> scans;
            do {
                scans = jedis.scan(cursor, params);
                keys.addAll(scans.getResult().stream().collect(Collectors.toSet()));
                cursor = scans.getCursor();
            } while (!cursor.equalsIgnoreCase("0"));
            if (null != keys && keys.size() > 0) {
                List<GlobalTransactionDO> globalTransactionDOs = new ArrayList<>();
                for (String globalKey : keys) {
                    GlobalTransactionDO globalTransactionDO =
                        JSON.parseObject(jedis.get(globalKey), GlobalTransactionDO.class);
                    if (null != globalTransactionDO && states.contains(globalTransactionDO.getStatus())) {
                        globalTransactionDOs.add(globalTransactionDO);
                    }
                }
                if (globalTransactionDOs.size() > 0) {
                    List<String> xids =
                        globalTransactionDOs.stream().map(GlobalTransactionDO::getXid).collect(Collectors.toList());
                    List<BranchTransactionDO> branchTransactionDOs = new ArrayList<>();
                    for (String xid : xids) {
                        Set<String> branches = range(jedis, getBranchListKeyByXid(xid));
                        if (null != branches && branches.size() > 0) {
                            for (String branchKey : branches) {
                                String branchJson = jedis.get(branchKey);
                                if (StringUtils.isNotBlank(branchJson)) {
                                    branchTransactionDOs.add(JSON.parseObject(branchJson, BranchTransactionDO.class));
                                }
                            }
                        }
                    }
                    Map<String, List<BranchTransactionDO>> branchTransactionDOsMap =
                        branchTransactionDOs.stream().collect(Collectors.groupingBy(BranchTransactionDO::getXid,
                            LinkedHashMap::new, Collectors.toList()));
                    return globalTransactionDOs.stream()
                        .map(globalTransactionDO -> getGlobalSession(globalTransactionDO,
                            branchTransactionDOsMap.get(globalTransactionDO.getXid())))
                        .collect(Collectors.toList());
                }
            }
        }
        return null;
    }

    @Override
    public List<GlobalSession> readSession(SessionCondition sessionCondition) {
        String globalSessionJson;
        try (Jedis jedis = JedisPooledFactory.getJedisInstance()) {
            if (StringUtils.isNotBlank(sessionCondition.getXid())) {
                globalSessionJson = jedis.get(getGlobalKeyByXid(sessionCondition.getXid()));
                if (StringUtils.isNotBlank(globalSessionJson)) {
                    GlobalSession session =
                        convertGlobalSession(JSON.parseObject(globalSessionJson, GlobalTransactionDO.class));
                    List<GlobalSession> globalSessions = new ArrayList<>();
                    globalSessions.add(session);
                    return globalSessions;
                }
            } else if (sessionCondition.getTransactionId() != null) {
                String global = jedis.get(getGlobalKeyByTransactionId(sessionCondition.getTransactionId()));
                if (StringUtils.isBlank(global)) {
                    return null;
                }
                GlobalTransactionDO globalTransactionDO = JSON.parseObject(global, GlobalTransactionDO.class);
                String branchKey = getBranchListKeyByXid(globalTransactionDO.getXid());
                Set<String> keys = range(jedis, branchKey);
                List<BranchTransactionDO> branchTransactionDOS = new ArrayList<>();
                if (null != keys && keys.size() > 0) {
                    for (String s : keys) {
                        branchTransactionDOS.add(JSON.parseObject(jedis.get(s), BranchTransactionDO.class));
                    }
                    GlobalSession globalSession = getGlobalSession(globalTransactionDO, branchTransactionDOS);
                    if (globalSession != null) {
                        List<GlobalSession> globalSessions = new ArrayList<>();
                        globalSessions.add(globalSession);
                        return globalSessions;
                    }
                }
            } else if (CollectionUtils.isNotEmpty(sessionCondition.getStatuses())) {
                return readSession(sessionCondition.getStatuses());
            }
        }
        return null;
    }

    private GlobalTransactionDO convertGlobalTransactionDO(SessionStorable session) {
        if (session == null || !(session instanceof GlobalSession)) {
            throw new IllegalArgumentException(
                "the parameter of SessionStorable is not available, SessionStorable:" + StringUtils.toString(session));
        }
        GlobalSession globalSession = (GlobalSession)session;

        GlobalTransactionDO globalTransactionDO = new GlobalTransactionDO();
        globalTransactionDO.setXid(globalSession.getXid());
        globalTransactionDO.setStatus(globalSession.getStatus().getCode());
        globalTransactionDO.setApplicationId(globalSession.getApplicationId());
        globalTransactionDO.setBeginTime(globalSession.getBeginTime());
        globalTransactionDO.setTimeout(globalSession.getTimeout());
        globalTransactionDO.setTransactionId(globalSession.getTransactionId());
        globalTransactionDO.setTransactionName(globalSession.getTransactionName());
        globalTransactionDO.setTransactionServiceGroup(globalSession.getTransactionServiceGroup());
        globalTransactionDO.setApplicationData(globalSession.getApplicationData());
        return globalTransactionDO;
    }

    private BranchTransactionDO convertBranchTransactionDO(SessionStorable session) {
        if (session == null || !(session instanceof BranchSession)) {
            throw new IllegalArgumentException(
                "the parameter of SessionStorable is not available, SessionStorable:" + StringUtils.toString(session));
        }
        BranchSession branchSession = (BranchSession)session;

        BranchTransactionDO branchTransactionDO = new BranchTransactionDO();
        branchTransactionDO.setXid(branchSession.getXid());
        branchTransactionDO.setBranchId(branchSession.getBranchId());
        branchTransactionDO.setBranchType(branchSession.getBranchType().name());
        branchTransactionDO.setClientId(branchSession.getClientId());
        branchTransactionDO.setResourceGroupId(branchSession.getResourceGroupId());
        branchTransactionDO.setTransactionId(branchSession.getTransactionId());
        branchTransactionDO.setApplicationData(branchSession.getApplicationData());
        branchTransactionDO.setResourceId(branchSession.getResourceId());
        branchTransactionDO.setStatus(branchSession.getStatus().getCode());
        return branchTransactionDO;
    }

    private GlobalSession convertGlobalSession(GlobalTransactionDO globalTransactionDO) {
        GlobalSession session =
            new GlobalSession(globalTransactionDO.getApplicationId(), globalTransactionDO.getTransactionServiceGroup(),
                globalTransactionDO.getTransactionName(), globalTransactionDO.getTimeout());
        session.setTransactionId(globalTransactionDO.getTransactionId());
        session.setXid(globalTransactionDO.getXid());
        session.setStatus(GlobalStatus.get(globalTransactionDO.getStatus()));
        session.setApplicationData(globalTransactionDO.getApplicationData());
        session.setBeginTime(globalTransactionDO.getBeginTime());
        return session;
    }

    private BranchSession convertBranchSession(BranchTransactionDO branchTransactionDO) {
        BranchSession branchSession = new BranchSession();
        branchSession.setXid(branchTransactionDO.getXid());
        branchSession.setTransactionId(branchTransactionDO.getTransactionId());
        branchSession.setApplicationData(branchTransactionDO.getApplicationData());
        branchSession.setBranchId(branchTransactionDO.getBranchId());
        branchSession.setBranchType(BranchType.valueOf(branchTransactionDO.getBranchType()));
        branchSession.setResourceId(branchTransactionDO.getResourceId());
        branchSession.setClientId(branchTransactionDO.getClientId());
        branchSession.setResourceGroupId(branchTransactionDO.getResourceGroupId());
        branchSession.setStatus(BranchStatus.get(branchTransactionDO.getStatus()));
        return branchSession;
    }

    private GlobalSession getGlobalSession(GlobalTransactionDO globalTransactionDO,
        List<BranchTransactionDO> branchTransactionDOs) {
        GlobalSession globalSession = convertGlobalSession(globalTransactionDO);
        // branch transactions
        if (branchTransactionDOs != null && branchTransactionDOs.size() > 0) {
            for (BranchTransactionDO branchTransactionDO : branchTransactionDOs) {
                globalSession.add(convertBranchSession(branchTransactionDO));
            }
        }
        return globalSession;
    }

    private Set<String> range(Jedis jedis, String Key) {
        Set<String> keys = new HashSet<>();
        List<String> redisBranchJson = null;
        int start = 0;
        int stop = logQueryLimit;
        for (;;) {
            redisBranchJson = jedis.lrange(Key, start, stop);
            if (null != redisBranchJson) {
                keys.addAll(redisBranchJson);
                start = keys.size();
                stop = start + logQueryLimit;
            } else {
                break;
            }
        }
        return keys;
    }

    private String getGlobalKeyByXid(String xid) {
        return DEFAULT_REDIS_SEATA_GLOBAL_PREFIX + xid;
    }

    private String getBranchListKeyByXid(String xid) {
        return DEFAULT_REDIS_SEATA_XID_BRANCHS_PREFIX + xid;
    }

    private String getGlobalKeyByTransactionId(Long transactionId) {
        return DEFAULT_SEATA_TRANSACTION_ID_GLOBAL_PREFIX + transactionId;
    }

    private String getBranchKey(Long branchId) {
        return DEFAULT_REDIS_SEATA_BRANCH_PREFIX + branchId;
    }

}

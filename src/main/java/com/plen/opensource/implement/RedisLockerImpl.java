package com.plen.opensource.implement;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import redis.clients.jedis.Jedis;

import java.util.UUID;

/**
 * Created by plen on 2017/6/17.
 */
@Service
public class RedisLockerImpl implements RedisLocker {

    private org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(RedisLocker.class);

    /**
     * 当前机器节点锁标识。
     */
    private static String redisIdentityKey = UUID.randomUUID().toString();

    /**
     * 获取当前机器节点在锁中的标示符。
     */
    public static String getRedisIdentityKey() {
        return redisIdentityKey;
    }

    /**
     * 等待获取锁的时间，可以根据当前任务的执行时间来设置。
     * 设置的太短，浪费CPU，设置的太长锁就不太公平。
     */
    private static final long WaitLockTimeSecond = 2000;
    /**
     * 重试获取锁的次数,可以根据当前任务的执行时间来设置。
     * 需要时间=RetryCount*(WaitLockTimeSecond/1000)
     */
    private static final int RetryCount = 10;

    @Autowired
    private Jedis jedis;

    /**
     * 带超时时间的redis lock.
     *
     * @param lockKeyExpireSecond 锁key在redis中的过去时间
     * @param lockKey             lock key
     * @param isWait              当获取不到锁时是否需要等待
     * @throws Exception lockKey is empty throw exception.
     */
    public Boolean acquireLockWithTimeout(int lockKeyExpireSecond, String lockKey, Boolean isWait) throws Exception {
        if (StringUtils.isEmpty(lockKey)) throw new Exception("lockKey is empty.");

        int tryCounts = 0;
        while (true) {
            Long status, expire = 0L;
            status = jedis.setnx(lockKey, redisIdentityKey);/**设置 lock key.*/
            if (status > 0) {
                expire = jedis.expire(lockKey, lockKeyExpireSecond);/**set  redis key expire time.*/
            }
            if (status > 0 && expire > 0) {
                logger.info(String.format("当前节点：%s,获取到锁：%s", getRedisIdentityKey(), lockKey));
                return true;/**获取到lock*/
            }

            try {
                if (isWait && tryCounts < RetryCount) {
                    tryCounts++;
                    synchronized (this) {/**借助object condition queue 来提高CPU利用率*/
                        logger.info(String.format("当前节点：%s,尝试等待获取锁：%s", getRedisIdentityKey(), lockKey));
                        this.wait(WaitLockTimeSecond); /**未能获取到lock，进行指定时间的wait再重试.*/
                    }
                } else {
                    return false;/**不需要等待，直接退出。*/
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 释放redis lock。
     *
     * @param lockKey lock key
     * @throws Exception lockKey is empty throw exception.
     */
    public Boolean releaseLockWithTimeout(String lockKey) throws Exception {
        if (StringUtils.isEmpty(lockKey)) throw new Exception("lockKey is empty.");

        long status = jedis.del(lockKey);
        if (status > 0) {
            logger.info(String.format("当前节点：%s,释放锁：%s 成功。", getRedisIdentityKey(), lockKey));
            return true;
        }
        logger.info(String.format("当前节点：%s,释放锁：%s 失败。", getRedisIdentityKey(), lockKey));
        return false;
    }
}

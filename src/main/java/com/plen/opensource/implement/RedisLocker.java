package com.plen.opensource.implement;

/**
 * Created by plen on 2017/6/17.
 */
public interface RedisLocker {
    Boolean acquireLockWithTimeout(int lockKeyExpireSecond, String lockKey, Boolean isWait) throws Exception;
    Boolean releaseLockWithTimeout(String lockKey) throws Exception;
}

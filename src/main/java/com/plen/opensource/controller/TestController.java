package com.plen.opensource.controller;

import com.plen.opensource.implement.RedisLocker;
import com.plen.opensource.implement.RedisLockerImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Created by plen on 2017/6/17.
 */
@RestController
public class TestController {

    @Autowired
    private RedisLocker redisLocker;


    @RequestMapping(value = "/lock", produces = "application/json;charset=utf-8", method = RequestMethod.GET)
    public boolean acrequireLock() {
        try {
            return redisLocker.acquireLockWithTimeout(50, "product:10100101:shopping", true);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }

    @RequestMapping(value = "/releaseLock", produces = "application/json;charset=utf-8", method = RequestMethod.GET)
    public boolean releaseLock() {
        try {
            return redisLocker.releaseLockWithTimeout("product:10100101:shopping");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }


    @RequestMapping(value = "/getLockIdentity", produces = "application/json;charset=utf-8", method = RequestMethod.GET)
    public String getCurrentNodeLockIdentity() {
        return RedisLockerImpl.getRedisIdentityKey();
    }

}

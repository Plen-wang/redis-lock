# redis-lock

* redis setnx command
* java object condition queue 条件队列
* retrycount 带有重试次数限制
* object wait time 带有超时时间的wait
* delete lock 删除远程锁
* acquire lock 申请lock
* release lock 释放lock
* demo 演示
* 锁的粒度问题，锁分解、锁分段
* github https://github.com/Plen-wang/redis-lock

## redis setnx 命令
redis setnx 命令特性
>当指定key不存在时才设置。也就是说，如果返回1说明你的命令被执行成功了，redis服务器中的key是你之前设置的值。如果返回0，说明你设置的key在redis服务器里已经存在。
```
            status = jedis.setnx(lockKey, redisIdentityKey);/**设置 lock key.*/
            if (status > 0) {
                expire = jedis.expire(lockKey, lockKeyExpireSecond);/**set  redis key expire time.*/
            }
```
如果设置成功了，才进行过期时间设置，防止你的retry lock重复设置这个过期时间，导致永远不过期。
## java object condition queue 条件队列
这里有一个小窍门，可以尽可能的最大化cpu利用率又可以解决公平性问题。

当你频繁retry的时候，要么while(true)死循环，然后加个Thread.sleep，或者CAS。前者存在一定线程上下文切换开销（Thread.sleep是不会释放出当前内置锁），而CAS在不清楚远程锁被占用多久的情况会浪费很多CPU计算周期，有可能一个任务计算个十几分钟，CPU不可能空转这么久。

这里我尝试使用condition queue条件队列特性来实现（当然肯定还有其他更优的方法）。
```
if (isWait && retryCounts < RetryCount) {
                    retryCounts++;
                    synchronized (this) {//借助object condition queue 来提高CPU利用率
                        logger.info(String.
                                format("t:%s,当前节点：%s,尝试等待获取锁：%s", Thread.currentThread().getId(), getRedisIdentityKey(), lockKey));
                        this.wait(WaitLockTimeSecond); //未能获取到lock，进行指定时间的wait再重试.
                    }
                } else if (retryCounts == RetryCount) {
                    logger.info(String.
                            format("t:%s,当前节点：%s,指定时间内获取锁失败：%s", Thread.currentThread().getId(), getRedisIdentityKey(), lockKey));
                    return false;
                } else {
                    return false;//不需要等待，直接退出。
                }
```

使用条件队列的好处就是，它虽然释放出了CPU但是也不会持有当前synchronized，这样就可以让其他并发进来的线程也可以获取到当前内置锁，然后形成队列。当wait时间到了被调度唤醒之后才会重新来申请synchronized锁。
简单讲就是不会再锁上等待而是在队列里等待。java object每一个对象都持有一个条件队列，与当前内置锁配合使用。
## retrycount 带有重试次数限制
等待远程redis lock肯定是需要一定重试机制，但是这种重试是需要一定的限制。
```
    /**
     * 重试获取锁的次数,可以根据当前任务的执行时间来设置。
     * 需要时间=RetryCount*(WaitLockTimeSecond/1000)
     */
    private static final int RetryCount = 10;
```
这种等待是需要用户指定的， if (isWait && retryCounts < RetryCount) ，当isWait为true才会进行重试。
## object wait time 带有超时时间的wait
object.wait(timeout),条件队列中的方法wait是需要一个waittime。
```
    /**
     * 等待获取锁的时间，可以根据当前任务的执行时间来设置。
     * 设置的太短，浪费CPU，设置的太长锁就不太公平。
     */
    private static final long WaitLockTimeSecond = 2000;
```
默认2000毫秒。
```
this.wait(WaitLockTimeSecond); //未能获取到lock，进行指定时间的wait再重试.
```
> 注意：this.wait虽然会blocking住，但是这里的内置锁是会立即释放出来的。所以，有时候我们可以借助这种特性来优化特殊场景。

## delete lock 删除远程锁
释放redis lock比较简单，直接del key就好了
```
long status = jedis.del(lockKey);
        if (status > 0) {
            logger.info(String.
                    format("t:%s,当前节点：%s,释放锁：%s 成功。", Thread.currentThread().getId(), getRedisIdentityKey(), lockKey));
            return true;
        }
```
一旦delete 之后，首先wait唤醒的线程将会获得锁。
## acquire lock 申请lock
```
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

        int retryCounts = 0;
        while (true) {
            Long status, expire = 0L;
            status = jedis.setnx(lockKey, redisIdentityKey);/**设置 lock key.*/
            if (status > 0) {
                expire = jedis.expire(lockKey, lockKeyExpireSecond);/**set  redis key expire time.*/
            }
            if (status > 0 && expire > 0) {
                logger.info(String.
                        format("t:%s,当前节点：%s,获取到锁：%s", Thread.currentThread().getId(), getRedisIdentityKey(), lockKey));
                return true;/**获取到lock*/
            }

            try {
                if (isWait && retryCounts < RetryCount) {
                    retryCounts++;
                    synchronized (this) {//借助object condition queue 来提高CPU利用率
                        logger.info(String.
                                format("t:%s,当前节点：%s,尝试等待获取锁：%s", Thread.currentThread().getId(), getRedisIdentityKey(), lockKey));
                        this.wait(WaitLockTimeSecond); //未能获取到lock，进行指定时间的wait再重试.
                    }
                } else if (retryCounts == RetryCount) {
                    logger.info(String.
                            format("t:%s,当前节点：%s,指定时间内获取锁失败：%s", Thread.currentThread().getId(), getRedisIdentityKey(), lockKey));
                    return false;
                } else {
                    return false;//不需要等待，直接退出。
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
```

## release lock 释放lock
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

## demo 演示
>2017-06-18 13:57:43.867  INFO 1444 --- [nio-8080-exec-1] c.plen.opensource.implement.RedisLocker  : t:23,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,获取到锁：product:10100101:shopping
2017-06-18 13:57:47.062  INFO 1444 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:57:49.063  INFO 1444 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:57:51.064  INFO 1444 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:57:53.066  INFO 1444 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:57:55.068  INFO 1444 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:57:57.069  INFO 1444 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:57:59.070  INFO 1444 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:58:01.071  INFO 1444 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:58:03.072  INFO 1444 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:58:05.073  INFO 1444 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:58:07.074  INFO 1444 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,指定时间内获取锁失败：product:10100101:shopping
2017-06-18 13:58:23.768  INFO 1444 --- [nio-8080-exec-6] c.plen.opensource.implement.RedisLocker  : t:28,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:58:25.769  INFO 1444 --- [nio-8080-exec-6] c.plen.opensource.implement.RedisLocker  : t:28,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:58:27.770  INFO 1444 --- [nio-8080-exec-6] c.plen.opensource.implement.RedisLocker  : t:28,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:58:29.772  INFO 1444 --- [nio-8080-exec-6] c.plen.opensource.implement.RedisLocker  : t:28,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:58:31.773  INFO 1444 --- [nio-8080-exec-6] c.plen.opensource.implement.RedisLocker  : t:28,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:58:33.774  INFO 1444 --- [nio-8080-exec-6] c.plen.opensource.implement.RedisLocker  : t:28,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:58:35.774  INFO 1444 --- [nio-8080-exec-6] c.plen.opensource.implement.RedisLocker  : t:28,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,获取到锁：product:10100101:shopping

thread 23 优先获取到对商品ID 10100101 进行修改，所以先锁住当前商品。
>t:23,当前节点：843d3ec0-9c22-4d8a-bcaa-745dba35b8a4,获取到锁：product:10100101:shopping

紧接着，thread 25也来对当前商品 10100101进行修改，所以在尝试获取锁。
>2017-06-18 13:50:11.021  INFO 4616 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：946b7250-29f3-459b-8320-62d31e6f1fc4,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:50:13.023  INFO 4616 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：946b7250-29f3-459b-8320-62d31e6f1fc4,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:50:15.026  INFO 4616 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：946b7250-29f3-459b-8320-62d31e6f1fc4,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:50:17.028  INFO 4616 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：946b7250-29f3-459b-8320-62d31e6f1fc4,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:50:19.030  INFO 4616 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：946b7250-29f3-459b-8320-62d31e6f1fc4,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:50:21.031  INFO 4616 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：946b7250-29f3-459b-8320-62d31e6f1fc4,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:50:23.035  INFO 4616 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：946b7250-29f3-459b-8320-62d31e6f1fc4,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:50:25.037  INFO 4616 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：946b7250-29f3-459b-8320-62d31e6f1fc4,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:50:27.041  INFO 4616 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：946b7250-29f3-459b-8320-62d31e6f1fc4,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:50:29.042  INFO 4616 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：946b7250-29f3-459b-8320-62d31e6f1fc4,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:50:35.289  INFO 4616 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：946b7250-29f3-459b-8320-62d31e6f1fc4,指定时间内获取锁失败：product:10100101:shopping

在进行了retry10次（2000毫秒，2秒）之后，获取失败，直接返回，等待下次任务调度开始。
>2017-06-18 13:58:07.074  INFO 1444 --- [nio-8080-exec-3] c.plen.opensource.implement.RedisLocker  : t:25,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,指定时间内获取锁失败：product:10100101:shopping
2017-06-18 13:58:23.768  INFO 1444 --- [nio-8080-exec-6] c.plen.opensource.implement.RedisLocker  : t:28,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:58:25.769  INFO 1444 --- [nio-8080-exec-6] c.plen.opensource.implement.RedisLocker  : t:28,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:58:27.770  INFO 1444 --- [nio-8080-exec-6] c.plen.opensource.implement.RedisLocker  : t:28,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:58:29.772  INFO 1444 --- [nio-8080-exec-6] c.plen.opensource.implement.RedisLocker  : t:28,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:58:31.773  INFO 1444 --- [nio-8080-exec-6] c.plen.opensource.implement.RedisLocker  : t:28,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:58:33.774  INFO 1444 --- [nio-8080-exec-6] c.plen.opensource.implement.RedisLocker  : t:28,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,尝试等待获取锁：product:10100101:shopping
2017-06-18 13:58:35.774  INFO 1444 --- [nio-8080-exec-6] c.plen.opensource.implement.RedisLocker  : t:28,当前节点：5f81f482-295a-4394-b8cb-d7282e51dd6e,获取到锁：product:10100101:shopping

thread 28 发起对商品 10100101 进行修改，retry6次之后获取到lock。

## 锁的粒度问题，锁分解、锁分段
这里的例子比较简单。如果在并发比较大的情况下是需要结合锁分解、锁分段来进行优化的。
修改商品，没有必要锁住整个商品库，只需要锁住你需要修改的指定ID的商品。也可以借鉴锁分段思路，将数据按照一定维度进行划分，然后加上不同维度的锁，可以提升CPU性能。可以根据商品catagory来设计段锁或者batch来设计段锁。

## github
源码已提交gihub，代码如有不对请多指教。
github地址：https://github.com/Plen-wang/redis-lock
package com.plen.opensource.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;


/**
 * Created by plen on 2017/6/17.
 */
@Configuration
public class JedisConfig {

    private static JedisPool jedisPool;

    @Bean
    public Jedis getBuild() {
        jedisPool = new JedisPool("192.168.1.100", 6379);
        Jedis jedis = jedisPool.getResource();
        return jedis;
    }
}

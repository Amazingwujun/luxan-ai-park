package com.lewin.luxanaipark.job;

import com.lewin.luxanaipark.camera.PassengerFlowInitializer;
import com.lewin.net.NetClient;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 流量 Job
 *
 * @author Jun
 * @since 1.0.0
 */
@Slf4j
@Component
public class TrafficJob implements Job {

    public static final Map<String, CompletableFuture<Boolean>> FUTURE_TASK_MAP = new ConcurrentHashMap<>();

    @Override
    public void execute(JobExecutionContext jobExecutionContext) {
        for (NetClient netClient : PassengerFlowInitializer.NET_CLIENT_MAP.values()) {
            var key = String.format("%s:%s", netClient.config().getHost(), netClient.config().getPort());
            var s = """
                    {"action":"clear_person_count"}
                    """;
            log.info("执行定时清空指令，目标地址[{}]", key);
            netClient.writeAndFlush(Unpooled.wrappedBuffer(s.getBytes(StandardCharsets.UTF_8)));
            var f = new CompletableFuture<Boolean>();
            FUTURE_TASK_MAP.put(key, f);

            // 超时处理
            f.orTimeout(3, TimeUnit.SECONDS);
            f.whenComplete((b, t) -> {
                FUTURE_TASK_MAP.remove(key);

                if (t != null) {
                    log.error("出现异常，断开连接: "+t.getMessage(), t);

                    netClient.close();
                }
            });
        }
    }
}

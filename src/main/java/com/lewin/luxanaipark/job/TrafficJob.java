package com.lewin.luxanaipark.job;

import com.lewin.commons.entity.LewinResult;
import com.lewin.commons.entity.Tuple2;
import com.lewin.luxanaipark.camera.PassengerFlowInitializer;
import com.lewin.luxanaipark.entity.CameraInfo;
import com.lewin.luxanaipark.entity.Traffic;
import com.lewin.luxanaipark.entity.TrafficParams;
import com.lewin.luxanaipark.service.ISceneService;
import com.lewin.luxanaipark.service.impl.HCNetServiceImpl;
import com.lewin.net.NetClient;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
    private final ISceneService sceneService;

    public TrafficJob(ISceneService sceneService) {
        this.sceneService = sceneService;
    }

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
                    log.error("出现异常，断开连接: " + t.getMessage(), t);

                    netClient.close();
                }
            });
        }

        // 海康相机处理
        for (var value : HCNetServiceImpl.HIK_TRAFFIC_INFO_MAP.values()) {
            for (var tuple2 : value) {
                var cameraInfo = tuple2.t0();
                var trafficParams = new TrafficParams().setIp(cameraInfo.getIp()).setPort(cameraInfo.getPort());
                LewinResult<Void> result = sceneService.clean(trafficParams);
                if (result.isOk()) {
                    log.info("[{}] 客流数据清理完成", cameraInfo.key());
                }else {
                    log.warn("[{}] 客流数据清理失败: {}", cameraInfo.key(), result.getMsg());
                }
            }
        }
    }
}

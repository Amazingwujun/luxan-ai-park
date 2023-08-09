package com.lewin.luxanaipark.service.impl;

import com.lewin.commons.constants.CommonResponseCode;
import com.lewin.commons.entity.LewinResult;
import com.lewin.commons.entity.Tuple2;
import com.lewin.luxanaipark.camera.PassengerFlowInitializer;
import com.lewin.luxanaipark.config.BizProperties;
import com.lewin.luxanaipark.entity.*;
import com.lewin.luxanaipark.handler.PassengerFlowHandler;
import com.lewin.luxanaipark.service.ISceneService;
import com.lewin.net.NetClient;
import io.netty.buffer.Unpooled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.lewin.luxanaipark.job.TrafficJob.FUTURE_TASK_MAP;

/**
 * 流量服务实现
 *
 * @author Jun
 * @since 1.0.0
 */
@Service
public class SceneServiceImpl implements ISceneService {

    private final BizProperties bizProperties;
    private final String streamUrlPrefix;

    public SceneServiceImpl(BizProperties bizProperties) {
        this.bizProperties = bizProperties;
        this.streamUrlPrefix = this.bizProperties.getStreamUrlPrefix();
    }

    @Override
    public LewinResult<List<TrafficVO>> traffic(String name) {
        Map<String, List<Tuple2<CameraInfo, Traffic>>> map = PassengerFlowHandler.TRAFFIC_INFO_MAP;
        List<Tuple2<CameraInfo, Traffic>> list = map.get(name);
        if (list == null) {
            return LewinResult.fail(CommonResponseCode.DATA_NOT_EXIST, "场景数据不存在");
        }

        //
        var rst = list.stream().map(t -> {
            var trafficVO = new TrafficVO()
                    .setIn(t.t1().getIn())
                    .setOut(t.t1().getOut())
                    .setName(t.t0().getName())
                    .setIp(t.t0().getIp())
                    .setPort(t.t0().getPort())
                    .setLocation(t.t0().getLocation())
                    .setStreamUrl(String.format("%s%s", this.streamUrlPrefix, t.t0().getName()));
            var k = String.format("%s:%s", trafficVO.getIp(), trafficVO.getPort());
            var netClient = PassengerFlowInitializer.NET_CLIENT_MAP.get(k);
            trafficVO.setOnlineFlag(netClient.isActive());
            return trafficVO;
        }).toList();
        return LewinResult.ok(rst);
    }

    @Override
    public LewinResult<List<Scene>> all() {
        List<Scene> sceneList = bizProperties.getSceneList();
        return LewinResult.ok(sceneList);
    }

    @Override
    public LewinResult<Void> clean(TrafficParams params) {
        var key = String.format("%s:%s", params.getIp(), params.getPort());
        var netClient = PassengerFlowInitializer.NET_CLIENT_MAP.get(key);
        if (netClient == null) {
            return LewinResult.fail(CommonResponseCode.DATA_NOT_EXIST, "未能找到相机连接对象");
        }
        if (!netClient.isActive()) {
            return LewinResult.fail(CommonResponseCode.EXECUTION_ERR, "设备离线，无法执行指令");
        }
        var s = """
                    {"action":"clear_person_count"}
                    """;
        netClient.writeAndFlush(Unpooled.wrappedBuffer(s.getBytes(StandardCharsets.UTF_8)));
        var f = new CompletableFuture<Boolean>();
        FUTURE_TASK_MAP.put(key, f);
        try {
            var result = f.get(3, TimeUnit.SECONDS);
            if (!result) {
                return LewinResult.fail(CommonResponseCode.EXECUTION_ERR, "客流数据清理失败!");
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            return LewinResult.fail(CommonResponseCode.EXECUTION_ERR, "设备响应超时");
        }
        return LewinResult.ok();
    }
}

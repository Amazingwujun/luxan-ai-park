package com.lewin.luxanaipark.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.lewin.commons.constants.CommonResponseCode;
import com.lewin.commons.entity.LewinResult;
import com.lewin.commons.entity.Tuple2;
import com.lewin.commons.exception.LewinException;
import com.lewin.luxanaipark.camera.PassengerFlowInitializer;
import com.lewin.luxanaipark.config.BizProperties;
import com.lewin.luxanaipark.entity.*;
import com.lewin.luxanaipark.handler.PassengerFlowHandler;
import com.lewin.luxanaipark.jna.CustomDeviceStateCallback;
import com.lewin.luxanaipark.jna.HCNetSDK;
import com.lewin.luxanaipark.service.IHCNetService;
import com.lewin.luxanaipark.service.ISceneService;
import com.sun.jna.NativeLong;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static com.lewin.luxanaipark.job.TrafficJob.FUTURE_TASK_MAP;

/**
 * 流量服务实现
 *
 * @author Jun
 * @since 1.0.0
 */
@Slf4j
@Service
public class SceneServiceImpl implements ISceneService {

    public static final int ISAPI_DATA_LEN = 1024 * 1024;
    public static final int ISAPI_STATUS_LEN = 4 * 4096;

    private final BizProperties bizProperties;
    private final String streamUrlPrefix;
    private final IHCNetService hcNetService;
    public final static XmlMapper XML_MAPPER = new XmlMapper();

    public SceneServiceImpl(BizProperties bizProperties,
                            IHCNetService hcNetService) {
        this.bizProperties = bizProperties;
        this.hcNetService = hcNetService;
        this.streamUrlPrefix = this.bizProperties.getStreamUrlPrefix();
    }

    @Override
    public LewinResult<List<TrafficVO>> traffic(String name) {
        var l1 = PassengerFlowHandler.DEEPCAM_TRAFFIC_INFO_MAP.get(name);
        var l2 = HCNetServiceImpl.HIK_TRAFFIC_INFO_MAP.get(name);
        if (ObjectUtils.isEmpty(l1) && l2.isEmpty()) {
            return LewinResult.fail(CommonResponseCode.DATA_NOT_EXIST, "场景数据不存在");
        }
        if (ObjectUtils.isEmpty(l1)) {
            l1 = new ArrayList<>();
        }
        if (ObjectUtils.isEmpty(l2)) {
            l2 = new ArrayList<>();
        }
        var list = Stream.concat(l1.stream(), l2.stream()).toList();

        //
        var rst = list.stream().map(t -> {
            var cameraInfo = t.t0();
            var traffic = t.t1();
            var trafficVO = new TrafficVO()
                    .setIn(traffic.getIn())
                    .setOut(traffic.getOut())
                    .setName(cameraInfo.getName())
                    .setIp(cameraInfo.getIp())
                    .setPort(cameraInfo.getPort())
                    .setLocation(cameraInfo.getLocation())
                    .setStreamUrl(String.format("%s%s", this.streamUrlPrefix, t.t0().rtspUrl()));
            switch (cameraInfo.getType()) {
                case deepcam -> {
                    var k = cameraInfo.key();
                    var netClient = PassengerFlowInitializer.NET_CLIENT_MAP.get(k);
                    trafficVO.setOnlineFlag(netClient.isActive());
                }
                case hik -> {
                    var k = cameraInfo.key();
                    trafficVO.setOnlineFlag(CustomDeviceStateCallback.DEVICE_ONLINE_MAP.containsKey(k));
                }
            }

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

        // 通过 key 检查设备类型
        var present = HCNetServiceImpl.HIK_TRAFFIC_INFO_MAP.values()
                .stream()
                .flatMap(Collection::stream)
                .map(Tuple2::t0)
                .map(CameraInfo::key)
                .anyMatch(t -> t.equals(key));
        if (present) {
            // 检查设备是否在线
            if (!CustomDeviceStateCallback.DEVICE_ONLINE_MAP.containsKey(key)) {
                return LewinResult.fail(CommonResponseCode.EXECUTION_ERR, "设备离线，无法执行指令");
            }

            var userId = hcNetService.findUserId(key);
            if (userId == null) {
                log.error("userId 为空!!!");
                throw new LewinException("hik device userId is null!!!");
            }

            if (resetCount(userId)) {
                return LewinResult.ok();
            }
            return LewinResult.fail(CommonResponseCode.EXECUTION_ERR, "客流数据清理失败!");
        }

        if (PassengerFlowInitializer.NET_CLIENT_MAP.containsKey(key)) {
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

        return LewinResult.fail(CommonResponseCode.DATA_NOT_EXIST, "未能找到相机连接对象");
    }

    private boolean resetCount(Integer userId) {
        // 1. 配置客流量统计数据重置
        // PUT /ISAPI/System/Video/inputs/channels/<channelID>/counting/resetCount
        var strURL = "PUT /ISAPI/System/Video/inputs/channels/1/counting/resetCount";

        // 输入指令组装
        var struXMLInput = new HCNetSDK.NET_DVR_XML_CONFIG_INPUT();
        struXMLInput.read();
        struXMLInput.dwSize = struXMLInput.size();
        var ptrUrl = new HCNetSDK.BYTE_ARRAY(strURL.length() + 1);
        System.arraycopy(strURL.getBytes(), 0, ptrUrl.byValue, 0, strURL.length());
        ptrUrl.write();
        struXMLInput.lpRequestUrl = ptrUrl.getPointer();
        struXMLInput.dwRequestUrlLen = strURL.length();
        struXMLInput.write();

        // 输出指令组装
        HCNetSDK.BYTE_ARRAY ptrStatusByte = new HCNetSDK.BYTE_ARRAY(ISAPI_STATUS_LEN);
        ptrStatusByte.read();

        HCNetSDK.BYTE_ARRAY ptrOutByte = new HCNetSDK.BYTE_ARRAY(ISAPI_DATA_LEN);
        ptrOutByte.read();

        var struXMLOutput = new HCNetSDK.NET_DVR_XML_CONFIG_OUTPUT();
        struXMLOutput.read();
        struXMLOutput.dwSize = struXMLOutput.size();
        struXMLOutput.lpOutBuffer = ptrOutByte.getPointer();
        struXMLOutput.dwOutBufferSize = ptrOutByte.size();
        struXMLOutput.lpStatusBuffer = ptrStatusByte.getPointer();
        struXMLOutput.dwStatusSize = ptrStatusByte.size();
        struXMLOutput.write();

        // 发送指令
        if (sdk().NET_DVR_STDXMLConfig(new NativeLong(userId), struXMLInput.getPointer(), struXMLOutput.getPointer())) {
            struXMLOutput.read();
            ptrOutByte.read();
            ptrStatusByte.read();

            var strStatus = new String(ptrStatusByte.byValue).trim();

            try {
                var node = XML_MAPPER.readTree(strStatus);
                Integer statusCode = Optional.of(node)
                        .map(t -> t.get("statusCode"))
                        .map(JsonNode::asInt)
                        .orElse(null);
                if (Objects.equals(1, statusCode)) {
                    // 成功
                    return true;
                }
            } catch (JsonProcessingException e) {
                log.error("strStatus 解析失败: "+ e.getMessage(), e);
                throw new LewinException(e);
            }
        } else {
            int err = hcNetService.sdk().NET_DVR_GetLastError();
            log.warn("NET_DVR_STDXMLConfig失败，错误号：{}", err);
        }

        return false;
    }

    private HCNetSDK sdk() {
        return HCNetSDK.INSTANCE;
    }
}

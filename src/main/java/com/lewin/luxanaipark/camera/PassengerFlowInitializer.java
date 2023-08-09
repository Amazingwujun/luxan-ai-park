package com.lewin.luxanaipark.camera;

import com.lewin.commons.exception.LewinException;
import com.lewin.luxanaipark.config.BizProperties;
import com.lewin.luxanaipark.entity.CameraInfo;
import com.lewin.luxanaipark.entity.Scene;
import com.lewin.luxanaipark.handler.PassengerFlowHandler;
import com.lewin.net.NetClient;
import io.netty.handler.codec.json.JsonObjectDecoder;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客流统计相机初始化对象
 *
 * @author Jun
 * @since 1.0.0
 */
@Slf4j
@Component
public class PassengerFlowInitializer {

    private final List<Scene> sceneList;
    public static final Map<String, NetClient> NET_CLIENT_MAP = new ConcurrentHashMap<>();

    public PassengerFlowInitializer(BizProperties bizProperties) {
        this.sceneList = bizProperties.getSceneList();

        // scene name, camera name 唯一性检查
        long sceneNameCount = this.sceneList.stream().map(Scene::getName).count();
        if (this.sceneList.size() != sceneNameCount) {
            throw new IllegalArgumentException("场景名称不允许重复");
        }
        var cameraInfoList = this.sceneList.stream().flatMap(t -> t.getCameraInfoList().stream()).toList();
        long cameraNameCount = cameraInfoList.stream().map(CameraInfo::getName).count();
        if (cameraInfoList.size() != cameraNameCount) {
            throw new IllegalArgumentException("相机名称不允许重复");
        }
        long cameraKeyCount = cameraInfoList.stream().map(CameraInfo::key).count();
        if (cameraInfoList.size() != cameraKeyCount) {
            throw new IllegalArgumentException("相机[ip:port]不允许重复");
        }
    }

    @PostConstruct
    public void init() {
        for (var scene : sceneList) {
            var cameraInfoList = scene.getCameraInfoList();
            if (ObjectUtils.isEmpty(cameraInfoList)) {
                log.warn("客流相机信息为空！");
                return;
            }

            for (var cameraInfo : cameraInfoList) {
                var netClient = new NetClient(ch -> {
                    var p = ch.pipeline();
                    p.addLast(new IdleStateHandler(0,0,15));
                    p.addLast(new JsonObjectDecoder());
                    p.addLast(new PassengerFlowHandler(scene.getName(), cameraInfo));
                }, cameraInfo.transferTo());
                netClient.start();

                NET_CLIENT_MAP.put(cameraInfo.key(), netClient);
            }
        }
    }
}

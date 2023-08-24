package com.lewin.luxanaipark.service.impl;

import com.lewin.commons.entity.Tuple2;
import com.lewin.commons.entity.Tuples;
import com.lewin.commons.exception.LewinException;
import com.lewin.luxanaipark.config.BizProperties;
import com.lewin.luxanaipark.constants.CameraTypeEnum;
import com.lewin.luxanaipark.entity.CameraInfo;
import com.lewin.luxanaipark.entity.Traffic;
import com.lewin.luxanaipark.jna.CustomDeviceStateCallback;
import com.lewin.luxanaipark.jna.CustomExceptionCallBack;
import com.lewin.luxanaipark.jna.CustomWarningCallback;
import com.lewin.luxanaipark.jna.HCNetSDK;
import com.lewin.luxanaipark.service.IHCNetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 海康设备网络服务接口实现
 *
 * @author Jun
 * @since 1.0.0
 */
@Slf4j
@Service
public class HCNetServiceImpl implements IHCNetService, DisposableBean {

    public static final Map<String, List<Tuple2<CameraInfo, Traffic>>> HIK_TRAFFIC_INFO_MAP = new ConcurrentHashMap<>();
    public static final ScheduledExecutorService SCHEDULED_EXECUTOR = Executors.newScheduledThreadPool(3);
    public static final Map<Integer, Tuple2<String, HCNetSDK.NET_DVR_DEVICEINFO_V30>> USER_ID_AND_DEVICE_INFO_MAP = new ConcurrentHashMap<>();
    private final CustomWarningCallback customWarningCallback;
    private final CustomExceptionCallBack customExceptionCallBack;
    private final CustomDeviceStateCallback customDeviceStateCallback;
    private final BizProperties bizProperties;

    public HCNetServiceImpl(CustomWarningCallback customWarningCallback,
                            CustomExceptionCallBack customExceptionCallBack,
                            CustomDeviceStateCallback customDeviceStateCallback,
                            BizProperties bizProperties) {
        this.customWarningCallback = customWarningCallback;
        this.customExceptionCallBack = customExceptionCallBack;
        this.customDeviceStateCallback = customDeviceStateCallback;
        this.bizProperties = bizProperties;
    }

    @PostConstruct
    public void launch() {
        // 此处的处理类似 PassengerFlowHandler
        var sceneList = bizProperties.getSceneList();
        for (var scene : sceneList) {
            var list = HIK_TRAFFIC_INFO_MAP.computeIfAbsent(scene.getName(), k -> new ArrayList<>());
            for (CameraInfo cameraInfo : scene.getCameraInfoList()) {
                if (CameraTypeEnum.hik == cameraInfo.getType()) {
                    list.add(Tuples.of(cameraInfo, new Traffic().setOut(0).setIn(0)));
                }
            }
        }

        // sdk 处理
        var cameraInfoList = sceneList.stream()
                .flatMap(l -> l.getCameraInfoList().stream())
                .filter(cameraInfo -> CameraTypeEnum.hik == cameraInfo.getType())
                .toList();

        if (ObjectUtils.isEmpty(cameraInfoList)) {
            log.info("camera connect info 列表为空");
            return;
        }

        // 执行初始化函数
        init();

        // 开始连接设备
        for (var cameraInfo : cameraInfoList) {
            try {
                loginAndStartDeploy(cameraInfo);
            } catch (Exception e) {
                log.warn("设备[{}:{}]注册失败，五秒后重新注册...", cameraInfo.getIp(), cameraInfo.getPort());
            }
        }
    }

    @Override
    public void init() {
        if (!sdk().NET_DVR_Init()) {
            throw new LewinException("sdk 初始化失败!!!");
        }

        // 日志文件
        if (!sdk().NET_DVR_SetLogToFile(3, System.getProperty("user.dir") + "/logs", true)) {
            throw new LewinException("sdk log 配置失败，错误码: {}", sdk().NET_DVR_GetLastError());
        }

        // 异常函数注册
        if (!sdk().NET_DVR_SetExceptionCallBack_V30(0, 0, customExceptionCallBack, null)) {
            throw new LewinException("sdk 异常函数注册失败");
        }

        var lsRes = sdk().NET_DVR_StartListen_V30(null, (short) 7200, customWarningCallback, null);
        if (lsRes.intValue() == -1) {
            throw new LewinException("sdk 事件监听函数注册失败");
        } else {
            log.info("sdk 事件监听函数注册成功");
        }

        // 设备状态函数注册
        var netDvrCheckDevState = new HCNetSDK.NET_DVR_CHECK_DEV_STATE();
        netDvrCheckDevState.fnStateCB = customDeviceStateCallback;
        netDvrCheckDevState.dwTimeout = 10 * 1000;
        if (!sdk().NET_DVR_StartGetDevState(netDvrCheckDevState)) {
            log.error("设备状态查询线程启动失败, 错误码: {}", sdk().NET_DVR_GetLastError());
        } else {
            log.info("设备状态查询函数注册成功");
        }

        if (!sdk().NET_DVR_SetDVRMessageCallBack_V30(customWarningCallback, null)) {
            throw new LewinException("布防回调函数设置失败!!!");
        }
    }

    @Override
    public void loginAndStartDeploy(CameraInfo cameraInfo) {
        final var ip = cameraInfo.getIp();
        final var port = cameraInfo.getPort();
        final var uname = cameraInfo.getUname();
        final var passwd = cameraInfo.getPasswd();
        final var key = cameraInfo.key();

        // 注册
        var deviceInfo = new HCNetSDK.NET_DVR_DEVICEINFO_V30();
        var userId = sdk().NET_DVR_Login_V30(ip, port.shortValue(), uname, passwd, deviceInfo);
        var iUserId = userId.intValue();
        if (iUserId == -1L) {
            log.error("设备[{}]注册失败, 错误码: {}. 服务将于五秒后尝试重连...", key, sdk().NET_DVR_GetLastError());
            SCHEDULED_EXECUTOR.schedule(() -> this.loginAndStartDeploy(cameraInfo), 5, TimeUnit.SECONDS);
            return;
        }

        // 用户 id 设备关联 map
        USER_ID_AND_DEVICE_INFO_MAP.put(iUserId, Tuples.of(key, deviceInfo));

        // 日志打印
        log.info("设备信息: {}", deviceInfo);
        log.info("注册到设备[{}]成功, 用户id: {}", key, iUserId);

        // 开始布防
        var errorCode = sdk().NET_DVR_SetupAlarmChan_V30(userId);
        if (errorCode.intValue() == -1) {
            log.error("设备布防失败, 错误码: {}", errorCode.intValue());
        } else {
            log.info("布防成功...");
        }
    }

    @Override
    public HCNetSDK sdk() {
        return HCNetSDK.INSTANCE;
    }

    @Override
    public Integer findUserId(String key) {
        for (var entry : USER_ID_AND_DEVICE_INFO_MAP.entrySet()) {
            var userId = entry.getKey();
            var k = entry.getValue().t0();
            if (key.equals(k)) {
                return userId;
            }
        }
        return null;
    }

    @Override
    public void destroy() {
        log.info("释放 SDK 资源");
        sdk().NET_DVR_Cleanup();
    }
}

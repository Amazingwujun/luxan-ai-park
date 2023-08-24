package com.lewin.luxanaipark.jna;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.lewin.commons.entity.Tuple3;
import com.lewin.commons.entity.Tuples;
import com.lewin.commons.exception.LewinException;
import com.lewin.luxanaipark.service.impl.HCNetServiceImpl;
import com.lewin.luxanaipark.utils.CommonUtils;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

import static com.lewin.luxanaipark.service.impl.SceneServiceImpl.XML_MAPPER;

/**
 * 设备状态回调.
 *
 * @author Jun
 * @since 1.0.0
 */
@Slf4j
@Component
public class CustomDeviceStateCallback implements HCNetSDK.FCheckDevStateCallBack, Runnable {

    public static final Map<String, Long> DEVICE_ONLINE_MAP = new ConcurrentHashMap<>();
    private final BlockingQueue<Tuple3<Integer, HCNetSDK.NET_DVR_WORKSTATE_V40, HCNetSDK.NET_DVR_DEVICEINFO_V30>> queue = new ArrayBlockingQueue<>(100_000);

    public CustomDeviceStateCallback() {
        new Thread(this).start();
    }

    @Override
    public void invoke(Pointer pUserdata, NativeLong lUserID, HCNetSDK.NET_DVR_WORKSTATE_V40 lpWorkState) {
        int userId = lUserID.intValue();
        var tuple2 = HCNetServiceImpl.USER_ID_AND_DEVICE_INFO_MAP.get(lUserID.intValue());
        var key = tuple2.t0();
        if (lpWorkState == null) {
            log.warn("已注册用户[{}]设备[{}:{}]工作状态为空", userId, key, CommonUtils.trimSerialNumber(tuple2.t1().sSerialNumber));

            // 设备离线，移除上线状态
            DEVICE_ONLINE_MAP.remove(key);
        } else {
            // 设备上线，添加上线状态
            DEVICE_ONLINE_MAP.put(key, System.currentTimeMillis());

            var deviceInfo = tuple2.t1();
            try {
                log.debug("用户id[{}]收到设备[{}]状态报文", userId, CommonUtils.trimSerialNumber(deviceInfo.sSerialNumber));
                queue.add(Tuples.of(userId, lpWorkState, deviceInfo));
            } catch (IllegalStateException e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    @Override
    public void run() {
        //noinspection InfiniteLoopStatement
        while (true) {
            try {
                var polled = queue.poll();
                if (polled == null) {
                    polled = queue.take();
                }

                final var userId = polled.t0();
                final var workState = polled.t1();
                final var deviceInfo = polled.t2();
                final var deviceSn = CommonUtils.trimSerialNumber(deviceInfo.sSerialNumber);

                // ip 通道检查
                var channelsEnable = new boolean[HCNetSDK.MAX_ANALOG_CHANNUM];
                IntByReference ibrBytesReturned = new IntByReference(0);//获取IP接入配置参数
                var m_strIpparaCfg = new HCNetSDK.NET_DVR_IPPARACFG();
                m_strIpparaCfg.write();
                Pointer lpIpParaConfig = m_strIpparaCfg.getPointer();
                var getDVRConfigSuc = sdk().NET_DVR_GetDVRConfig(new NativeLong(userId), HCNetSDK.NET_DVR_GET_IPPARACFG, new NativeLong(0), lpIpParaConfig, m_strIpparaCfg.size(), ibrBytesReturned);
                m_strIpparaCfg.read();
                if (!getDVRConfigSuc) {
                    //设备不支持,则表示没有IP通道
                    for (int iChannum = 0; iChannum < deviceInfo.byChanNum; iChannum++) {
                        channelsEnable[iChannum] = true;
                    }
                } else {
                    // 包含IP通道
                    for (int iChannum = 0; iChannum < deviceInfo.byChanNum; iChannum++) {
                        channelsEnable[iChannum] = m_strIpparaCfg.byAnalogChanEnable[iChannum] == 1;
                    }
                }

                // 显示模拟通道状态
                var lst = new ArrayList<ChannelStatus>();
                for (byte i = 0; i < deviceInfo.byChanNum; i++) {
                    if (channelsEnable[i]) {
                        ChannelStatus channelStatus = new ChannelStatus();
                        channelStatus.id = i;

                        var chanStatic = workState.struChanStatic[i];

                        // 是否录像
                        if (0 == chanStatic.byRecordStatic) {
                            channelStatus.recordStatic = "不录像";
                        } else {
                            channelStatus.recordStatic = "录像";
                        }

                        // 信号
                        if (0 == chanStatic.bySignalStatic) {
                            channelStatus.signalStatic = "正常";
                        } else {
                            if (1 == chanStatic.bySignalStatic) {
                                channelStatus.signalStatic = "信号丢失";
                            }
                        }

                        // 硬件状体
                        if (0 == chanStatic.byHardwareStatic) {
                            channelStatus.hardwareStatic = "正常";
                        } else {
                            channelStatus.hardwareStatic = "异常";
                        }

                        // 连接数
                        channelStatus.linkNum = chanStatic.dwLinkNum;
                        // 当前码率
                        channelStatus.bitRate = chanStatic.dwBitRate;
                        // ipc连接数
                        channelStatus.ipcLinkNum = chanStatic.dwIPLinkNum;

                        lst.add(channelStatus);
                    }
                }

                String deviceStatusStr;
                switch (workState.dwDeviceStatic) {
                    case 0 -> deviceStatusStr = "正常";
                    case 1 -> deviceStatusStr = "CPU占用率太高，超过85%";
                    case 2 -> deviceStatusStr = "硬件错误";
                    default -> deviceStatusStr = "未知";
                }

                if (lst.isEmpty()) {
                    log.debug("""
                            
                            -----------------------------------
                            设备[{}]状态 {}
                            -----------------------------------
                            """, deviceSn, deviceStatusStr);
                } else {
                    var msg = new StringBuilder();
                    for (int i = 0; i < lst.size(); i++) {
                        var item = lst.get(i);
                        if (i == lst.size() - 1) {
                            msg.append(item.toString());
                            break;
                        }
                        msg.append(item.toString()).append("\r\n");
                    }
                    log.debug(
                            """
                            
                            -----------------------------------
                            设备[{}]状态 {}
                            {}
                            -----------------------------------
                            """, deviceSn, deviceStatusStr, msg);
                }
            } catch (Throwable e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    private HCNetSDK sdk() {
        return HCNetSDK.INSTANCE;
    }

    @Data
    @Accessors(chain = true)
    private static class ChannelStatus {

        private int id;

        private String recordStatic;

        private String signalStatic;

        private String hardwareStatic;

        private int linkNum;

        private int bitRate;

        private int ipcLinkNum;

        @Override
        public String toString() {
            return "通道号 [%s] 录像状态[%s] 信号状态[%s] 硬件状态[%s] 连接数[%s] 当前码率(bps)[%s] IPC链接数[%s]".formatted(
                    id, recordStatic, signalStatic, hardwareStatic, linkNum, bitRate, ipcLinkNum
            );
        }
    }
}

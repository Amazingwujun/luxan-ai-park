package com.lewin.luxanaipark.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.lewin.commons.entity.Tuple2;
import com.lewin.commons.entity.Tuples;
import com.lewin.commons.utils.JSON;
import com.lewin.luxanaipark.entity.CameraInfo;
import com.lewin.luxanaipark.entity.Traffic;
import com.lewin.luxanaipark.job.TrafficJob;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 客流数据处理器
 *
 * @author Jun
 * @since 1.0.0
 */
@Slf4j
public class PassengerFlowHandler extends SimpleChannelInboundHandler<ByteBuf> {

    public static final Map<String, List<Tuple2<CameraInfo, Traffic>>> DEEPCAM_TRAFFIC_INFO_MAP = new ConcurrentHashMap<>();
    private final CameraInfo cameraInfo;
    private final String sceneName;

    public PassengerFlowHandler(String sceneName, CameraInfo cameraInfo) {
        this.sceneName = sceneName;
        this.cameraInfo = cameraInfo;

        var alreadyExistFlag = false;
        List<Tuple2<CameraInfo, Traffic>> list = DEEPCAM_TRAFFIC_INFO_MAP.computeIfAbsent(sceneName, k -> new ArrayList<>());
        for (Tuple2<CameraInfo, Traffic> tuple2 : list) {
            if (cameraInfo == tuple2.t0()) {
                alreadyExistFlag = true;
                break;
            }
        }
        if (!alreadyExistFlag) {
            list.add(Tuples.of(cameraInfo, new Traffic().setIn(0).setOut(0)));
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("成功连接至[{}:{}]", cameraInfo.getIp(), cameraInfo.getPort());

        // 发起登录报文
        var signInObj = JSON.createObjectNode()
                .put("action", "login")
                .put("user_name", cameraInfo.getUname())
                .put("pwd", cameraInfo.getPasswd());
        ctx.writeAndFlush(Unpooled.wrappedBuffer(JSON.writeValueAsBytes(signInObj)));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        var str = msg.toString(StandardCharsets.UTF_8);
        var json = JSON.readTree(str);

        // 捕获 action
        var action = Optional.of(json)
                .map(t -> t.get("action"))
                .map(JsonNode::asText)
                .orElse(null);
        if (action == null) {
            log.warn("响应 action 为空: {}", str);
            return;
        }
        switch (action) {
            case "login" -> {
                var ret = Optional.of(json)
                        .map(t -> t.get("ret"))
                        .map(JsonNode::asText)
                        .orElse(null);
                if ("login successfully".equals(ret)) {
                    // 登录成功
                    log.info("登入[{}]成功", cameraInfo.key());

                    // 启动定时任务，发送人数查询指令
                    ctx.executor().scheduleAtFixedRate(() -> {
                        // 发起客流查询指令
                        if (ctx.isRemoved()) {
                            return;
                        }
                        var s = """
                                {"action":"get_person_count"}
                                """;
                        ctx.writeAndFlush(Unpooled.wrappedBuffer(s.getBytes(StandardCharsets.UTF_8)));
                    }, 0, 500, TimeUnit.MILLISECONDS);
                } else {
                    log.info("登入[{}]失败, 响应：{}", cameraInfo.key(), str);
                }
            }
            case "get_person_count" -> {
                var now = Optional.of(json)
                        .map(t -> t.get("ret"))
                        .map(t -> JSON.treeToValue(t, Traffic.class))
                        .orElse(null);
                if (now == null) {
                    log.warn("客流量数据获取失败，响应：{}", str);
                    return;
                }

                var list = DEEPCAM_TRAFFIC_INFO_MAP.get(sceneName);
                var hasElement = false;
                for (var tuple2 : list) {
                    if (tuple2.t0().equals(this.cameraInfo)) {
                        hasElement = true;
                        tuple2.t1().setIn(now.getIn()).setOut(now.getOut());
                    }
                }
                if (!hasElement) {
                    list.add(new Tuple2<>(cameraInfo, now));
                }
            }
            case "clear_person_count" -> {
                var ret = Optional.of(json)
                        .map(t -> t.get("ret"))
                        .map(JsonNode::asText)
                        .orElse(null);
                if ("ok".equalsIgnoreCase(ret)) {
                    log.debug("[{}-[{}]]客流数据清空成功", cameraInfo.getName(), cameraInfo.key());
                    TrafficJob.FUTURE_TASK_MAP.computeIfPresent(cameraInfo.key(), (s, t) -> {
                        t.complete(true);
                        return t;
                    });
                } else {
                    log.warn("[{}-[{}]]客流数据清空失败", cameraInfo.getName(), cameraInfo.key());
                    TrafficJob.FUTURE_TASK_MAP.computeIfPresent(cameraInfo.key(), (s, t) -> {
                        t.complete(false);
                        return t;
                    });
                }
            }
            default -> log.warn("Unknown action: {}", str);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error(cause.getMessage(), cause);
        ctx.close();
    }

    /**
     * 心跳、握手事件处理
     *
     * @param ctx {@link ChannelHandlerContext}
     * @param evt {@link IdleStateEvent}
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        String host = socketAddress.getAddress().getHostAddress();
        int port = socketAddress.getPort();

        log.info("用户定义事件触发: {}", evt);

        if (evt instanceof IdleStateEvent ise) {
            if (IdleState.ALL_IDLE.equals(ise.state())) {
                log.info("R[{}:{}] 心跳超时", host, port);

                // 关闭连接
                ctx.close();
            }
        }
    }
}

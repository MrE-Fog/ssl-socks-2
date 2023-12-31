package awesome.socks.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Timer;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import awesome.socks.client.bean.ClientOptions;
import awesome.socks.client.handler.ClientHttpServerHandler;
import awesome.socks.client.handler.SSSClientChannelHandler;
import awesome.socks.client.util.ClientMonitor;
import awesome.socks.common.bean.App;
import awesome.socks.common.handler.TrackingHandler;
import awesome.socks.common.metadata.Handler;
import awesome.socks.common.metadata.Tracking;
import awesome.socks.common.util.Monitor.Unit;
import awesome.socks.common.util.ResourcesUtils;
import awesome.socks.common.util.SslUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.XSlf4j;

/**
 * test : <p>
 * curl --socks5-hostname localhost:8888 --proxy-user sss_test:03AC674216F3E15C761EE1A5E255F067953623C8B388B4459E13F978D7C846F4 "http://www.baidu.com"
 * 
 * @author awesome
 */
@XSlf4j
public class Client extends App {
    
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final EventExecutorGroup gtsGroup = new DefaultEventExecutorGroup(1);

    private final Timer timer = new Timer();
    
    private ClientMonitor monitor = null;
    
    @Override
    public void start() {
        try {
            log.info("start...");
            ClientOptions clientOptions = ClientOptions.getInstance();
            runSSS(clientOptions);
            runHttp(clientOptions, monitor);
            log.info("start finished");
        } catch (Throwable e) {
            log.error("", e);
            shutdown();
        }
    }

    @Override
    public void shutdown() {
        log.info("shutdown...");
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        gtsGroup.shutdownGracefully();
        timer.cancel();
    }
    
    private void runSSS(ClientOptions clientOptions) {
        final GlobalTrafficShapingHandler globalTrafficShapingHandler = (clientOptions.monitorIntervals() > 0)
                ? new GlobalTrafficShapingHandler(gtsGroup.next())
                : null;
        if (globalTrafficShapingHandler != null) {
            monitor = new ClientMonitor(Unit.B, globalTrafficShapingHandler.trafficCounter());
            monitor.run(timer, clientOptions.monitorIntervals());
        } else {
            gtsGroup.shutdownGracefully();
        }
        log.info("use monitor : {}", (globalTrafficShapingHandler != null));

        try {
            final SslContext sslContext = clientOptions.useSsl() ? getSslContext(clientOptions.localFingerprintsAlgorithm()) : null;
            log.info("use ssl : {}", (sslContext != null));
            
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(Handler.BASE_LOGGER.getCh())
                    .childOption(ChannelOption.AUTO_READ, false)
                    .childHandler(new ChannelInitializer<SocketChannel>() {

                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {

                            ch.pipeline().addLast(Handler.TRACKING_INIT_LOGGER.getName(),
                                    Handler.TRACKING_INIT_LOGGER.getCh());

                            ch.pipeline().addLast(Tracking.STEP_1, new TrackingHandler(Tracking.STEP_1));
                            ch.pipeline().addLast(Handler.TRACKING_LOGGER.getName() + Tracking.STEP_1,
                                    Handler.TRACKING_LOGGER.getCh());
                            ch.pipeline().addLast(Handler.MSG_LOGGER.getName(), Handler.MSG_LOGGER.getCh());
                            ch.pipeline().addLast("IdleStateHandler",
                                    new IdleStateHandler(30, 30, 0, TimeUnit.SECONDS));

                            ch.pipeline().addLast(Tracking.STEP_2, new TrackingHandler(Tracking.STEP_2));
                            ch.pipeline().addLast(Handler.TRACKING_LOGGER.getName() + Tracking.STEP_2,
                                    Handler.TRACKING_LOGGER.getCh());
                            ch.pipeline().addLast("SSSClientHandler",
                                    new SSSClientChannelHandler(globalTrafficShapingHandler, sslContext,
                                            clientOptions.serverHost(), clientOptions.serverPort()));
                        }
                    });
            ChannelFuture channelFuture = bootstrap.bind(clientOptions.localHost(), clientOptions.localPort())
                    .addListener(new GenericFutureListener<ChannelFuture>() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if(future.isSuccess()) {
                        log.info("future is success");
                    } else {
                        log.info("future is not success");
                    }
                }
            }).sync();
            channelFuture.channel().closeFuture().addListener(new GenericFutureListener<ChannelFuture>() {

                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    log.info("future is close");
                }
            });
        } catch (InterruptedException | SSLException e) {
            log.error("", e);
        }
    }
    
    private void runHttp(ClientOptions clientOptions, ClientMonitor monitor) {
        final Client client = this;
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {

                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            log.info("SocketChannel.id = {}", ch.id());

                            ch.pipeline().addLast(Handler.HTTP_LOGGER.getName(), Handler.HTTP_LOGGER.getCh())
                                    .addLast("HttpServerCodec", new HttpServerCodec())
                                    .addLast("HttpServerHandler", new ClientHttpServerHandler(client, monitor));
                        }
                    });
            ChannelFuture channelFuture = bootstrap.bind(clientOptions.httpHost(), clientOptions.httpPort())
                    .addListener(new GenericFutureListener<ChannelFuture>() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        log.info("future is success");
                    } else {
                        log.info("future is not success");
                    }
                }
            }).sync();
            channelFuture.channel().closeFuture().addListener(new GenericFutureListener<ChannelFuture>() {

                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    log.info("future is close");
                }
            });
        } catch (InterruptedException e) {
            log.error("", e);
        }
    }
    
    private SslContext getSslContext(String algorithm) throws SSLException {
        String fingerprints = null;
        File file = new File(ResourcesUtils.getResourceFile("ssl/fingerprint"));
        if(file.exists()) {
            try(FileInputStream fis = new FileInputStream(file);
                    InputStreamReader isr = new InputStreamReader(fis, CharsetUtil.UTF_8);
                    BufferedReader br = new BufferedReader(isr);){
                fingerprints = br.readLine();
            } catch (IOException e) {
                log.error("", e);
            }
        }
        return SslUtils.genTrustClientSslContext(algorithm, fingerprints);
    }
    
    public static void main(String[] args) {
        new Client().start();
    }
}

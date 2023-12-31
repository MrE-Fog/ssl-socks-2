package awesome.socks.client.util;

import java.net.InetSocketAddress;

import awesome.socks.client.bean.ClientOptions;
import awesome.socks.client.handler.request.Socks5RequestTestHandler;
import awesome.socks.common.metadata.Handler;
import awesome.socks.common.metadata.HandlerName;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5ClientEncoder;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.XSlf4j;

/**
 * 
 * @author awesome
 */
@XSlf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Socks5Utils {
    
    public static DefaultPromise<Void> connectTest() {
        
        ClientOptions clientOptions = ClientOptions.getInstance();
        
        NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup(1);
        Bootstrap bootstrap = new Bootstrap();

        EventExecutorGroup eventExecutorGroup = new DefaultEventExecutorGroup(1);
        DefaultPromise<Void> promise = new DefaultPromise<>(eventExecutorGroup.next());
        
        try {
            bootstrap.group(eventLoopGroup)
                    .remoteAddress(new InetSocketAddress("localhost", clientOptions.localPort()))
                    .option(ChannelOption.AUTO_READ, false)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {

                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline()
                                    .addLast(Handler.TEST_LOGGER.getName(), Handler.TEST_LOGGER.getCh())
                                    .addLast(HandlerName.CLIENT_SOCKS5_ENCODER, Socks5ClientEncoder.DEFAULT)
                                    .addLast(HandlerName.CLIENT_TEST,
                                            new Socks5RequestTestHandler(clientOptions.localTestUrl(), promise,
                                                    clientOptions.serverUsername(), clientOptions.serverPassword()));
                        }
                    });
            ChannelFuture cf = bootstrap.connect().addListener(new GenericFutureListener<ChannelFuture>() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if(future.isSuccess()) {
                        log.info("future is success");
                        future.channel().read();
                    } else {
                        log.info("future is not success");
                    }
                }
            }).sync();
            cf.channel().closeFuture().sync();
            return promise;
        } catch (InterruptedException e) {
            log.error("", e);
            return null;
        } finally {
            eventLoopGroup.shutdownGracefully();
        }        
    }
}

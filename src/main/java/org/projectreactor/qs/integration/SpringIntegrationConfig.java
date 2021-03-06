package org.projectreactor.qs.integration;

import org.projectreactor.qs.service.MessageCountService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.integration.config.ConsumerEndpointFactoryBean;
import org.springframework.integration.ip.tcp.TcpReceivingChannelAdapter;
import org.springframework.integration.ip.tcp.connection.AbstractConnectionFactory;
import org.springframework.integration.ip.tcp.connection.TcpNetServerConnectionFactory;
import org.springframework.integration.ip.tcp.serializer.ByteArrayLengthHeaderSerializer;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import reactor.core.Environment;
import reactor.io.encoding.Codec;
import reactor.io.encoding.LengthFieldCodec;
import reactor.io.encoding.StandardCodecs;
import reactor.net.config.ServerSocketOptions;

/**
 * JavaConfig that merges external, XML-based Spring Integration components with Reactor SI components.
 *
 * @author Jon Brisbin
 */
@Configuration
@ImportResource("org/projectreactor/qs/integration/common.xml")
public class SpringIntegrationConfig {

	@Value("${reactor.port:3000}")
	private int    tcpPort;
	@Value("${reactor.dispatcher:ringBuffer}")
	private String dispatcher;

	@Bean
	public MessageHandler messageHandler(final MessageCountService msgCnt) {
		return new MessageHandler() {
			@Override
			public void handleMessage(Message<?> msg) throws MessagingException {
				msgCnt.increment();
			}
		};
	}

	/**
	 * Count up messages as they come through the channel.
	 *
	 * @param messageHandler
	 * 		the counter service
	 * @param output
	 * 		the output channel
	 *
	 * @return new {@link org.springframework.integration.config.ConsumerEndpointFactoryBean}
	 */
	@Bean
	public ConsumerEndpointFactoryBean messageCounterEndpoint(MessageHandler messageHandler,
	                                                          MessageChannel output) {
		ConsumerEndpointFactoryBean factoryBean = new ConsumerEndpointFactoryBean();
		factoryBean.setInputChannel(output);
		factoryBean.setHandler(messageHandler);
		return factoryBean;
	}

	/**
	 * Reactor-based TCP InboundChannelAdapter. Since we're testing with random data, we can't really decode anything, so
	 * the {@link reactor.io.encoding.PassThroughCodec} just skips over any bytes to pretend it's dealt with them.
	 *
	 * @param env
	 * 		the Reactor {@code Environment} in use
	 * @param output
	 * 		the output channel
	 *
	 * @return the new {@code ReactorTcpInboundChannelAdapter}
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Bean
	@Profile("reactor")
	public ReactorTcpInboundChannelAdapter reactorTcpChannelAdapter(Environment env,
	                                                                MessageHandler messageHandler,
	                                                                MessageChannel output) {
		ReactorTcpInboundChannelAdapter tcp = new ReactorTcpInboundChannelAdapter(env,
		                                                                          tcpPort,
		                                                                          dispatcher,
		                                                                          messageHandler);

		Codec delegateCodec = StandardCodecs.BYTE_ARRAY_CODEC;
		Codec codec = new LengthFieldCodec(delegateCodec);

		return tcp.setOutput(output)
		          .setServerSocketOptions(new ServerSocketOptions()
				                                  .tcpNoDelay(true)
				                                  .backlog(1000)
				                                  .rcvbuf(1048576)
				                                  .sndbuf(1048576))
		          .setCodec(codec);
	}

	@Bean
	@Profile("si")
	public TcpReceivingChannelAdapter siTcpChannelAdapter(MessageChannel output,
	                                                      AbstractConnectionFactory siTcpConnectionFactory) {
		TcpReceivingChannelAdapter adapter = new TcpReceivingChannelAdapter();
		adapter.setOutputChannel(output);
		adapter.setConnectionFactory(siTcpConnectionFactory);
		return adapter;
	}

	@Bean
	@Profile("si")
	public AbstractConnectionFactory siTcpConnectionFactory(
			@Qualifier("threadPoolTaskExecutor") TaskExecutor taskExecutor
	) {
		TcpNetServerConnectionFactory connectionFactory = new TcpNetServerConnectionFactory(tcpPort);
		connectionFactory.setTaskExecutor(taskExecutor);
		connectionFactory.setLookupHost(false);
		ByteArrayLengthHeaderSerializer deserializer = new ByteArrayLengthHeaderSerializer();
		deserializer.setMaxMessageSize(3000);
		connectionFactory.setDeserializer(deserializer);
		return connectionFactory;
	}

}

package spring.config;

import com.github.novicezk.midjourney.ProxyProperties;
import com.github.novicezk.midjourney.service.TaskStoreService;
import com.github.novicezk.midjourney.service.TranslateService;
import com.github.novicezk.midjourney.service.store.InMemoryTaskStoreServiceImpl;
import com.github.novicezk.midjourney.service.store.RedisTaskStoreServiceImpl;
import com.github.novicezk.midjourney.service.translate.BaiduTranslateServiceImpl;
import com.github.novicezk.midjourney.service.translate.GPTTranslateServiceImpl;
import com.github.novicezk.midjourney.support.Task;
import com.github.novicezk.midjourney.support.TaskMixin;
import com.github.novicezk.midjourney.wss.WebSocketStarter;
import com.github.novicezk.midjourney.wss.bot.BotMessageListener;
import com.github.novicezk.midjourney.wss.bot.BotWebSocketStarter;
import com.github.novicezk.midjourney.wss.user.UserMessageListener;
import com.github.novicezk.midjourney.wss.user.UserWebSocketStarter;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;
import com.qcloud.cos.transfer.TransferManager;
import com.qcloud.cos.transfer.TransferManagerConfiguration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class BeanConfig {

	@Bean
	TranslateService translateService(ProxyProperties properties) {
		return switch (properties.getTranslateWay()) {
			case BAIDU -> new BaiduTranslateServiceImpl(properties.getBaiduTranslate());
			case GPT -> new GPTTranslateServiceImpl(properties);
			default -> prompt -> prompt;
		};
	}

    @Bean
    TaskStoreService taskStoreService(ProxyProperties proxyProperties, RedisConnectionFactory redisConnectionFactory) {
        ProxyProperties.TaskStore.Type type = proxyProperties.getTaskStore().getType();
        Duration timeout = proxyProperties.getTaskStore().getTimeout();
        return switch (type) {
            case IN_MEMORY -> new InMemoryTaskStoreServiceImpl(timeout);
            case REDIS -> new RedisTaskStoreServiceImpl(timeout, taskRedisTemplate(redisConnectionFactory));
        };
    }

	@Bean
	RedisTemplate<String, Task> taskRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
		RedisTemplate<String, Task> redisTemplate = new RedisTemplate<>();
		redisTemplate.setConnectionFactory(redisConnectionFactory);
		redisTemplate.setKeySerializer(new StringRedisSerializer());
		redisTemplate.setHashKeySerializer(new StringRedisSerializer());
		redisTemplate.setValueSerializer(new Jackson2JsonRedisSerializer<>(Task.class));
		return redisTemplate;
	}

    @Bean
    WebSocketStarter webSocketStarter(ProxyProperties properties) {
        return properties.getDiscord().isUserWss() ? new UserWebSocketStarter(properties) : new BotWebSocketStarter(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "mj.discord", name = "user-wss", havingValue = "true")
    UserMessageListener userMessageListener() {
        return new UserMessageListener();
    }

    @Bean
    @ConditionalOnProperty(prefix = "mj.discord", name = "user-wss", havingValue = "false")
    BotMessageListener botMessageListener() {
        return new BotMessageListener();
    }

    @Bean
    ApplicationRunner enableMetaChangeReceiverInitializer(WebSocketStarter webSocketStarter) {
        return args -> webSocketStarter.start();
    }

	@Bean
	Jackson2ObjectMapperBuilderCustomizer jackson2ObjectMapperBuilderCustomizer(ProxyProperties properties) {
		if (properties.isIncludeTaskExtended()) {
			return builder -> {
			};
		}
		return builder -> builder.mixIn(Task.class, TaskMixin.class);
	}


    @Bean
    // 创建 TransferManager 实例，这个实例用来后续调用高级接口
    public TransferManager createTransferManager(COSClient cosClient) {

        // 创建一个 COSClient 实例，这是访问 COS 服务的基础实例。
        // 详细代码参见本页: 简单操作 -> 创建 COSClient
//        COSClient cosClient = createCOSClient();

        // 自定义线程池大小，建议在客户端与 COS 网络充足（例如使用腾讯云的 CVM，同地域上传 COS）的情况下，设置成16或32即可，可较充分的利用网络资源
        // 对于使用公网传输且网络带宽质量不高的情况，建议减小该值，避免因网速过慢，造成请求超时。
        ExecutorService threadPool = Executors.newFixedThreadPool(32);

        // 传入一个 threadpool, 若不传入线程池，默认 TransferManager 中会生成一个单线程的线程池。
        TransferManager transferManager = new TransferManager(cosClient, threadPool);

        // 设置高级接口的配置项
        // 分块上传阈值和分块大小分别为 5MB 和 1MB
        TransferManagerConfiguration transferManagerConfiguration = new TransferManagerConfiguration();
        transferManagerConfiguration.setMultipartUploadThreshold(3 * 1024 * 1024);
        transferManagerConfiguration.setMinimumUploadPartSize(1 * 1024 * 1024);
        transferManagerConfiguration.setMultipartCopyThreshold(3 * 1024 * 1024);
        transferManager.setConfiguration(transferManagerConfiguration);

        return transferManager;
    }

    @Bean
    // 创建 COSClient 实例，这个实例用来后续调用请求
    public COSClient createCOSClient(ProxyProperties properties) {
        ProxyProperties.CosConfig cosConfig = properties.getCosConfig();


//        String secretId = SpringUtil.getProperty("secretId");
//        String secretKey = SpringUtil.getProperty("secretKey");
        // 设置用户身份信息。
        // SECRETID 和 SECRETKEY 请登录访问管理控制台 https://console.cloud.tencent.com/cam/capi 进行查看和管理
        COSCredentials cred = new BasicCOSCredentials(cosConfig.getSecretId(), cosConfig.getSecretKey());

        // ClientConfig 中包含了后续请求 COS 的客户端设置：
        ClientConfig clientConfig = new ClientConfig();

        // 设置 bucket 的地域
        // COS_REGION 请参见 https://cloud.tencent.com/document/product/436/6224
        clientConfig.setRegion(new Region("ap-beijing"));

        // 设置请求协议, http 或者 https
        // 5.6.53 及更低的版本，建议设置使用 https 协议
        // 5.6.54 及更高版本，默认使用了 https
        clientConfig.setHttpProtocol(HttpProtocol.https);
        String cosSuffix = "cos.accelerate.myqcloud.com";
        clientConfig.setEndPointSuffix(cosSuffix);
        // 以下的设置，是可选的：

        // 设置 socket 读取超时，默认 30s
        clientConfig.setSocketTimeout(30 * 1000);
        // 设置建立连接超时，默认 30s
        clientConfig.setConnectionTimeout(30 * 1000);

        // 如果需要的话，设置 http 代理，ip 以及 port
//        clientConfig.setHttpProxyIp("httpProxyIp");
//        clientConfig.setHttpProxyPort(80);

        // 生成 cos 客户端。
        return new COSClient(cred, clientConfig);
    }

}

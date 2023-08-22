package com.github.novicezk.midjourney.service;

import cn.hutool.cache.CacheUtil;
import cn.hutool.cache.impl.TimedCache;
import cn.hutool.core.exceptions.CheckedUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.novicezk.midjourney.Constants;
import com.github.novicezk.midjourney.ProxyProperties;
import com.github.novicezk.midjourney.enums.TaskStatus;
import com.github.novicezk.midjourney.support.Task;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.springframework.http.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Slf4j
@Service
public class NotifyServiceImpl implements NotifyService {
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private final ThreadPoolTaskExecutor executor;
	private final TimedCache<String, Object> taskLocks = CacheUtil.newTimedCache(Duration.ofHours(1).toMillis());

	private ProxyProperties properties;

	public NotifyServiceImpl(ProxyProperties properties) {
		this.executor = new ThreadPoolTaskExecutor();
		this.executor.setCorePoolSize(properties.getNotifyPoolSize());
		this.executor.setThreadNamePrefix("TaskNotify-");
		this.executor.initialize();
		this.properties = properties;
	}

	@Override
	public void notifyTaskChange(Task task) {
		String notifyHook = task.getPropertyGeneric(Constants.TASK_PROPERTY_NOTIFY_HOOK);
		if (CharSequenceUtil.isBlank(notifyHook)) {
			return;
		}
		String taskId = task.getId();
		TaskStatus taskStatus = task.getStatus();
		Object taskLock = this.taskLocks.get(taskId, (CheckedUtil.Func0Rt<Object>) Object::new);

			this.executor.execute(() -> {
				synchronized (taskLock) {
					try {
                        String paramsStr = OBJECT_MAPPER.writeValueAsString(task);
						ResponseEntity<String> responseEntity = postJson(notifyHook, paramsStr);
						if (responseEntity.getStatusCode() == HttpStatus.OK) {
							log.debug("推送任务变更成功, 任务ID: {}, status: {}, notifyHook: {}", taskId, taskStatus, notifyHook);
						} else {
							log.warn("推送任务变更失败, 任务ID: {}, notifyHook: {}, code: {}, msg: {}", taskId, notifyHook, responseEntity.getStatusCodeValue(), responseEntity.getBody());
						}
					} catch (Exception e) {
						log.warn("推送任务变更失败, 任务ID: {}, notifyHook: {}, 描述: {}", taskId, notifyHook, e.getMessage());
					}
				}
			});
	}

  private ResponseEntity<String> postJson (String notifyHook, String paramsJson) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<String> httpEntity = new HttpEntity<>(paramsJson, headers);
    return new RestTemplate().postForEntity(notifyHook, httpEntity, String.class);
  }


  @Override
    public void updateCosTask(Task task) {
        String imageUrl = task.getImageUrl();
        log.info(imageUrl);
        if (StrUtil.isBlank(imageUrl)) {
            return;
        }
      HttpUrl httpUrl = HttpUrl.get(imageUrl);
      String url= httpUrl.newBuilder().host("cdn.discord.warape.top").build().url().toString();
      task.setCosImageUrl(url);

    }

}

package com.github.novicezk.midjourney.service;

import cn.hutool.cache.CacheUtil;
import cn.hutool.cache.impl.TimedCache;
import cn.hutool.core.exceptions.CheckedUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.novicezk.midjourney.Constants;
import com.github.novicezk.midjourney.ProxyProperties;
import com.github.novicezk.midjourney.enums.TaskStatus;
import com.github.novicezk.midjourney.ProxyProperties;
import com.github.novicezk.midjourney.support.Task;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.UploadResult;
import com.qcloud.cos.transfer.TransferManager;
import com.qcloud.cos.transfer.Upload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class NotifyServiceImpl implements NotifyService {
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private final ThreadPoolTaskExecutor executor;
	private final TimedCache<String, Object> taskLocks = CacheUtil.newTimedCache(Duration.ofHours(1).toMillis());

	public NotifyServiceImpl(ProxyProperties properties) {
		this.executor = new ThreadPoolTaskExecutor();
		this.executor.setCorePoolSize(properties.getNotifyPoolSize());
		this.executor.setThreadNamePrefix("TaskNotify-");
		this.executor.initialize();
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
		try {
			String paramsStr = OBJECT_MAPPER.writeValueAsString(task);
			this.executor.execute(() -> {
				synchronized (taskLock) {
					try {
						updateCosTask(task);
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
		} catch (JsonProcessingException e) {
			log.warn("推送任务变更失败, 任务ID: {}, notifyHook: {}, 描述: {}", taskId, notifyHook, e.getMessage());
		}
	}

  private ResponseEntity<String> postJson (String notifyHook, String paramsJson) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<String> httpEntity = new HttpEntity<>(paramsJson, headers);
    return new RestTemplate().postForEntity(notifyHook, httpEntity, String.class);
  }






	private void updateCosTask (Task task) {
		String taskId = task.getId();
		URL url;
		URLConnection connection;
		String imageUrl = task.getImageUrl();
		log.info(imageUrl);
		if (StrUtil.isBlank(imageUrl)) {
			return;
		}
		try {
			url = new URL(imageUrl);
			connection = url.openConnection();
		} catch (Exception e) {
			log.error("根据URL地址获取图片异常 地址:{}", imageUrl, e);
			return;
		}
		try (InputStream inputStream = connection.getInputStream()) {
			ProxyProperties.CosConfig cosConfig = properties.getCosConfig();
			String extension = getExtension(url);
			String key = taskId + "." + extension;
			task.setCosImageUrl(cosConfig.getDomain() + "/" + key);

			ObjectMetadata objectMetadata = new ObjectMetadata();
			objectMetadata.setContentDisposition("inline");
			objectMetadata.setContentType("image/" + extension);
//            objectMetadata.setContentLength();
			Map<String, String> userMetadata = new HashMap<>();
//            userMetadata.put("id", taskId);
//            userMetadata.put("name", key);
			userMetadata.put("taskStatus", task.getStatus().name());
//      userMetadata.put("failReason", task.getFailReason());
			userMetadata.put("notifyHook", task.getPropertyGeneric(Constants.TASK_PROPERTY_NOTIFY_HOOK));
			userMetadata.put("relatedTaskId", task.getPropertyGeneric(Constants.TASK_PROPERTY_RELATED_TASK_ID));
			userMetadata.put("state", task.getState());
			objectMetadata.setUserMetadata(userMetadata);

			PutObjectRequest putObjectRequest = new PutObjectRequest(cosConfig.getBucketName(), key, inputStream, objectMetadata);
			Upload upload = transferManager.upload(putObjectRequest);
			log.info("upload:{}", JSONUtil.toJsonStr(upload));
			UploadResult uploadResult = upload.waitForUploadResult();
			log.info("uploadResult:{}", JSONUtil.toJsonStr(uploadResult));
		} catch (Exception e) {
			log.error("cos上传异常", e);
		}
	}

  public static String getExtension (URL url) {
    String path = url.getPath();
    int lastDotPos = path.lastIndexOf('.');

    // 如果找到了'.'，则截取其后的部分作为文件扩展名；否则，文件扩展名为空
    return lastDotPos != -1 ? path.substring(lastDotPos + 1) : "";
  }

//    public static void main(String[] args) throws Exception {
//        URL url = new URL("https://cdn.discordapp.com/attachments/1114559240116371456/1114840474742685696/1114840427066052660.png");
//
//        String extension = getExtension(url);
//
//        URLConnection connection = url.openConnection();
//        InputStream inputStream = connection.getInputStream();
//        String key = "123" + "." + extension;
//        ObjectMetadata metadata = new ObjectMetadata();
//        metadata.setContentDisposition("inline");
//        metadata.setContentType("image/" + extension);
//        PutObjectRequest putObjectRequest = new PutObjectRequest("bj-1259324451", key, inputStream, metadata);
//        putObjectRequest.withMetadata(metadata);
//        Upload upload = CosUploadUtils.createTransferManager("", "").upload(putObjectRequest);
//
//        System.out.println(JSONUtil.toJsonStr(upload));
//        UploadResult uploadResult = upload.waitForUploadResult();
//        System.out.println(JSONUtil.toJsonStr(uploadResult));
//    }

}

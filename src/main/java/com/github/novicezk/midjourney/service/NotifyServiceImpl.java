package com.github.novicezk.midjourney.service;

import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.novicezk.midjourney.ProxyProperties;
import com.github.novicezk.midjourney.support.Task;
import com.github.novicezk.midjourney.util.CosUploadUtils;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.UploadResult;
import com.qcloud.cos.transfer.TransferManager;
import com.qcloud.cos.transfer.Upload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyServiceImpl implements NotifyService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    @Autowired
    private ProxyProperties properties;


    @Override
    public void notifyTaskChange(Task task) {
        String notifyHook = task.getNotifyHook();
        if (CharSequenceUtil.isBlank(notifyHook)) {
            return;
        }
        String taskId = task.getId();
        String imageUrl = task.getImageUrl();
        URL url;
        URLConnection connection;
        TransferManager transferManager = null;
        try {
            url = new URL(imageUrl);
            connection = url.openConnection();
        } catch (Exception e) {
            log.error("根据URL地址获取图片异常", e);
            return;
        }

        try (InputStream inputStream = connection.getInputStream()) {
            ProxyProperties.CosConfig cosConfig = properties.getCosConfig();
            String extension = getExtension(url);
            String key = taskId + "." + extension;
            transferManager = CosUploadUtils.createTransferManager();
            task.setCosImageUrl(cosConfig.getDomain() + "/" + key);
            String paramsStr = OBJECT_MAPPER.writeValueAsString(task);
            ObjectMetadata objectMetadata = new ObjectMetadata();
            objectMetadata.setContentDisposition("inline");
            objectMetadata.setContentType("image/" + extension);
            objectMetadata.setUserMetadata(JSONUtil.toBean(paramsStr, Map.class));

            PutObjectRequest putObjectRequest = new PutObjectRequest(cosConfig.getBucketName(), key, inputStream, objectMetadata);
            Upload upload = transferManager.upload(putObjectRequest);
            UploadResult uploadResult = upload.waitForUploadResult();
            log.debug("uploadResult:{}", uploadResult);
            ResponseEntity<String> responseEntity = postJson(notifyHook, paramsStr);
            if (responseEntity.getStatusCode() == HttpStatus.OK) {
                log.debug("推送任务变更成功, 任务ID: {}, status: {}", taskId, task.getStatus());
            } else {
                log.warn("推送任务变更失败, 任务ID: {}, code: {}, msg: {}", taskId, responseEntity.getStatusCodeValue(), responseEntity.getBody());
            }
        } catch (Exception e) {
            log.warn("推送任务变更失败, 任务ID: {}, 描述: {}", taskId, e.getMessage());
        } finally {
            if (transferManager != null) {
                transferManager.shutdownNow(true);
            }
        }
    }

    private ResponseEntity<String> postJson(String notifyHook, String paramsJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> httpEntity = new HttpEntity<>(paramsJson, headers);
        return new RestTemplate().postForEntity(notifyHook, httpEntity, String.class);
    }

    public static String getExtension(URL url) {
        String path = url.getPath();
        int lastDotPos = path.lastIndexOf('.');

        // 如果找到了'.'，则截取其后的部分作为文件扩展名；否则，文件扩展名为空
        return lastDotPos != -1 ? path.substring(lastDotPos + 1) : "";
    }

    public static void main(String[] args) throws Exception {
        URL url = new URL("https://cdn.discordapp.com/attachments/1114559240116371456/1114840474742685696/1114840427066052660.png");

        String extension = getExtension(url);

        URLConnection connection = url.openConnection();
        InputStream inputStream = connection.getInputStream();
        String key = "123" + "." + extension;
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentDisposition("inline");
        metadata.setContentType("image/" + extension);
        PutObjectRequest putObjectRequest = new PutObjectRequest("bj-1259324451", key, inputStream, metadata);
        putObjectRequest.withMetadata(metadata);
        Upload upload = CosUploadUtils.createTransferManager().upload(putObjectRequest);

        System.out.println(JSONUtil.toJsonStr(upload));
        UploadResult uploadResult = upload.waitForUploadResult();
        System.out.println(JSONUtil.toJsonStr(uploadResult));
    }

}

package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.ChatFileService;
import com.dataweave.master.application.ChatFileService.ChatFileVO;
import com.dataweave.master.application.ChatFileService.Loaded;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 聊天附件文件 REST 端点（chat-attachments）。
 *
 * <p>上传走 WebFlux 原生 multipart（{@link FilePart}，非 Spring MVC {@code MultipartFile}）。
 * 前端拿到 {@code id} 后随 {@code forwardedProps.dataweave.attachments} 引用进对话。
 */
@RestController
@RequestMapping("/api/chat/files")
public class ChatFileController {

    private final ChatFileService chatFileService;

    public ChatFileController(ChatFileService chatFileService) {
        this.chatFileService = chatFileService;
    }

    /** 上传聊天附件（multipart：file=文件）。校验 + sha256 去重 + 存储，返回可引用的轻量元数据。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ApiResponse<ChatFileVO>> upload(@RequestPart("file") FilePart file) {
        Long tenantId = currentTenantId();
        Long userId = TenantContext.userId();
        String filename = file.filename();
        MediaType mt = file.headers().getContentType();
        String mime = mt != null ? mt.toString() : null;
        return DataBufferUtils.join(file.content())
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                })
                .map(bytes -> ApiResponse.ok(
                        chatFileService.upload(filename, mime, bytes, tenantId, userId)));
    }

    /** 下载附件（按 id=sha256）。供 Agent / 用户回取原始字节。 */
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        Loaded loaded = chatFileService.load(id);
        ChatFileVO meta = loaded.meta();
        MediaType mt = meta.mime() != null
                ? MediaType.parseMediaType(meta.mime())
                : MediaType.APPLICATION_OCTET_STREAM;
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(meta.name(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mt)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .body(loaded.bytes());
    }

    private static Long currentTenantId() {
        Long tenantId = TenantContext.tenantId();
        return tenantId != null ? tenantId : 1L;
    }
}

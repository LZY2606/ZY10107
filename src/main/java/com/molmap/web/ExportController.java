package com.molmap.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.molmap.service.ExportService;

@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ExportService export;

    public ExportController(ExportService export) {
        this.export = export;
    }

    @GetMapping("/package.zip")
    public ResponseEntity<byte[]> zip() {
        byte[] body = export.exportZip();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"molmap-export.zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }
}

package com.molmap.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.molmap.service.BatchService;

@RestController
@RequestMapping("/api/batch")
public class BatchController {

    private final BatchService batch;

    public BatchController(BatchService batch) {
        this.batch = batch;
    }

    @PostMapping
    public BatchService.BatchResponse run(@RequestBody BatchService.BatchRequest req) {
        return batch.run(req, System.currentTimeMillis());
    }
}

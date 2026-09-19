package com.molmap.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.molmap.service.CompositionService;
import com.molmap.store.CompositionState;
import com.molmap.store.CompositionRepository;

import java.util.List;

@RestController
@RequestMapping("/api/compose")
public class CompositionController {

    private final CompositionService composition;
    private final CompositionRepository repo;

    public CompositionController(CompositionService composition, CompositionRepository repo) {
        this.composition = composition;
        this.repo = repo;
    }

    @PostMapping
    public CompositionService.CompositionResponse compose(@RequestBody CompositionService.ComposeRequest req) {
        return composition.compose(req, System.currentTimeMillis());
    }

    @GetMapping("/history")
    public List<CompositionState> history() {
        return repo.findAll();
    }
}

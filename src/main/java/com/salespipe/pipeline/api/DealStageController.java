package com.salespipe.pipeline.api;

import com.salespipe.pipeline.domain.DealStage;
import com.salespipe.pipeline.infra.DealStageRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/deal-stages")
public class DealStageController {
    private final DealStageRepository repo;
    public DealStageController(DealStageRepository repo) { this.repo = repo; }

    @GetMapping
    public List<DealStage> list() { return repo.findAllByOrderByPositionAsc(); }
}

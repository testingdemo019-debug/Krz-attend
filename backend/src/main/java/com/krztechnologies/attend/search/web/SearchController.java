package com.krztechnologies.attend.search.web;

import com.krztechnologies.attend.common.security.SignedIn;
import com.krztechnologies.attend.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The topbar's global search. */
@RestController
@RequestMapping("/api/v1/search")
@Validated
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @Operation(summary = "Employees, events, review items, departments and shifts the user may see (pages: frontend)")
    @SignedIn
    @GetMapping
    public SearchService.Results search(@RequestParam(required = false) @Size(max = 100) String q) {
        return search.search(q);
    }
}

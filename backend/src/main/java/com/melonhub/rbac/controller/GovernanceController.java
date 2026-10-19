package com.melonhub.rbac.controller;

import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.security.AdminAccess;
import com.melonhub.rbac.security.AdminPrincipal;
import com.melonhub.rbac.service.AuditSealService;
import com.melonhub.rbac.service.ConsoleService;
import com.melonhub.rbac.service.GovernanceService;
import com.melonhub.rbac.service.RbacService;
import com.melonhub.rbac.service.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Collection;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Governance console API: approvals, reviews, webhooks, manifests, bulk import, reports and system status. */
@RestController
@RequestMapping("/api/admin")
public class GovernanceController {
    private final GovernanceService g;
    private final ConsoleService console;
    private final WebhookService webhooks;
    private final AuditSealService auditSeal;
    private final RbacService rbac;

    public GovernanceController(GovernanceService g, ConsoleService console, WebhookService webhooks,
                                AuditSealService auditSeal, RbacService rbac) {
        this.g = g; this.console = console; this.webhooks = webhooks; this.auditSeal = auditSeal; this.rbac = rbac;
    }

    private static Collection<Long> visible(AdminPrincipal principal) { return principal.allApplications() ? null : principal.applicationIds(); }
    private static int page(int page) { return Math.max(1, page); }
    private static int size(int size) { return Math.min(200, Math.max(1, size)); }
    private static long appOf(Map<String,Object> row) { return ((Number) row.get("applicationId")).longValue(); }
    private static Map<String,Object> ok() { return Map.of("ok", true); }

    // ---- access requests ----
    @GetMapping("/access-requests") Object requests(@RequestParam(required=false) Long applicationId, @RequestParam(required=false) String status,
                                                    @RequestParam(required=false) String keyword, @RequestParam(defaultValue="1") int page,
                                                    @RequestParam(defaultValue="20") int size, HttpServletRequest req) {
        var principal = AdminAccess.of(req);
        if (applicationId != null) AdminAccess.readApplication(req, applicationId);
        return g.requests(applicationId, visible(principal), status, keyword, page(page), size(size));
    }
    @PostMapping("/access-requests/{id}/approve") Object approve(@PathVariable long id, @Valid @RequestBody Requests.RequestApprove body,
                                                                 HttpServletRequest req) {
        AdminAccess.writeApplication(req, appOf(g.request(id)));
        return g.approve(id, body, AdminAccess.of(req).actor());
    }
    @PostMapping("/access-requests/{id}/reject") Object reject(@PathVariable long id, @Valid @RequestBody Requests.RequestReject body,
                                                               HttpServletRequest req) {
        AdminAccess.writeApplication(req, appOf(g.request(id)));
        return g.reject(id, body, AdminAccess.of(req).actor());
    }

    // ---- access reviews ----
    @GetMapping("/reviews") Object reviews(@RequestParam(required=false) Long applicationId, @RequestParam(required=false) String status,
                                           HttpServletRequest req) {
        var principal = AdminAccess.of(req);
        if (applicationId != null) AdminAccess.readApplication(req, applicationId);
        return g.reviews(applicationId, visible(principal), status);
    }
    @PostMapping("/reviews") Object createReview(@Valid @RequestBody Requests.ReviewCreate body, HttpServletRequest req) {
        return g.createReview(body, AdminAccess.writeApplication(req, body.applicationId()).actor());
    }
    @GetMapping("/reviews/{id}") Object review(@PathVariable long id, HttpServletRequest req) {
        var review = g.review(id);
        AdminAccess.readApplication(req, appOf(review));
        return review;
    }
    @GetMapping("/reviews/{id}/items") Object reviewItems(@PathVariable long id, @RequestParam(required=false) String decision,
                                                          @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,
                                                          HttpServletRequest req) {
        AdminAccess.readApplication(req, appOf(g.review(id)));
        return g.reviewItems(id, decision, page(page), size(size));
    }
    @PutMapping("/reviews/{id}/items/{itemId}") Object decideItem(@PathVariable long id, @PathVariable long itemId,
                                                                  @Valid @RequestBody Requests.ReviewItemDecision body, HttpServletRequest req) {
        AdminAccess.writeApplication(req, appOf(g.review(id)));
        g.decideReviewItem(id, itemId, body, AdminAccess.of(req).actor());
        return ok();
    }
    @PostMapping("/reviews/{id}/close") Object closeReview(@PathVariable long id, HttpServletRequest req) {
        AdminAccess.writeApplication(req, appOf(g.review(id)));
        return g.closeReview(id, AdminAccess.of(req).actor());
    }

    // ---- webhooks ----
    @GetMapping("/applications/{id}/webhooks") Object webhooks(@PathVariable long id, HttpServletRequest req) {
        AdminAccess.readApplication(req, id);
        return webhooks.webhooks(id);
    }
    @PostMapping("/applications/{id}/webhooks") Object createWebhook(@PathVariable long id, @Valid @RequestBody Requests.Webhook body,
                                                                     HttpServletRequest req) {
        return webhooks.create(id, body, AdminAccess.writeApplication(req, id).actor());
    }
    @PutMapping("/webhooks/{id}") Object updateWebhook(@PathVariable long id, @Valid @RequestBody Requests.Webhook body, HttpServletRequest req) {
        AdminAccess.writeApplication(req, appOf(webhooks.webhook(id)));
        webhooks.update(id, body, AdminAccess.of(req).actor());
        return ok();
    }
    @DeleteMapping("/webhooks/{id}") Object deleteWebhook(@PathVariable long id, HttpServletRequest req) {
        AdminAccess.writeApplication(req, appOf(webhooks.webhook(id)));
        webhooks.delete(id, AdminAccess.of(req).actor());
        return ok();
    }
    @GetMapping("/webhooks/{id}/deliveries") Object deliveries(@PathVariable long id, @RequestParam(defaultValue="1") int page,
                                                               @RequestParam(defaultValue="20") int size, HttpServletRequest req) {
        AdminAccess.readApplication(req, appOf(webhooks.webhook(id)));
        return webhooks.deliveries(id, page(page), size(size));
    }
    @PostMapping("/webhooks/{id}/test") Object testWebhook(@PathVariable long id, HttpServletRequest req) {
        AdminAccess.writeApplication(req, appOf(webhooks.webhook(id)));
        webhooks.test(id, AdminAccess.of(req).actor());
        return ok();
    }

    // ---- permission manifest ----
    @GetMapping("/manifest") Object exportManifest(@RequestParam long applicationId, @RequestParam(required=false) Long departmentId,
                                                   HttpServletRequest req) {
        AdminAccess.readApplication(req, applicationId);
        return g.exportManifest(applicationId, departmentId);
    }
    @PostMapping("/manifest/import") Object importManifest(@RequestParam long applicationId, @Valid @RequestBody Requests.Manifest body,
                                                           HttpServletRequest req) {
        return g.importManifest(applicationId, body, AdminAccess.writeApplication(req, applicationId).actor());
    }

    // ---- bulk operations and reports ----
    @PostMapping("/users/import") Object importUsers(@Valid @RequestBody Requests.UserImport body, HttpServletRequest req) {
        return g.importUsers(body, AdminAccess.platformWrite(req).actor());
    }
    @GetMapping("/permissions/who") Object permissionHolders(@RequestParam long applicationId, @RequestParam String code,
                                                             @RequestParam(required=false) Long departmentId, HttpServletRequest req) {
        AdminAccess.readApplication(req, applicationId);
        return g.permissionHolders(applicationId, code, departmentId);
    }
    @GetMapping("/assignments/expiring") Object expiring(@RequestParam(defaultValue="7") int days, @RequestParam(required=false) Long applicationId,
                                                         HttpServletRequest req) {
        var principal = AdminAccess.of(req);
        if (days < 1 || days > 365) throw com.melonhub.rbac.exception.ApiException.bad("天数需在 1-365 之间");
        if (applicationId != null) AdminAccess.readApplication(req, applicationId);
        return g.expiringAssignments(days, visible(principal), applicationId);
    }
    @PutMapping("/applications/{id}/rate-limit") Object rateLimit(@PathVariable long id, @Valid @RequestBody Requests.RateLimitUpdate body,
                                                                  HttpServletRequest req) {
        rbac.applicationRateLimit(id, body.perSecond(), AdminAccess.platformWrite(req).actor());
        return ok();
    }

    // ---- search, status and audit chain ----
    @GetMapping("/search") Object search(@RequestParam String q, HttpServletRequest req) {
        return console.search(q, visible(AdminAccess.of(req)));
    }
    @GetMapping("/system/status") Object status(HttpServletRequest req) {
        return console.status(visible(AdminAccess.of(req)));
    }
    @GetMapping("/audit/verify") Object verifyAudit(@RequestParam(required=false) Long fromSeq,
                                                    @RequestParam(defaultValue="5000") int limit, HttpServletRequest req) {
        AdminAccess.of(req);
        return auditSeal.verify(fromSeq, Math.min(50_000, Math.max(1, limit)));
    }
}

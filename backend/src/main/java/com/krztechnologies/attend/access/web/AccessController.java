package com.krztechnologies.attend.access.web;

import com.krztechnologies.attend.access.domain.AccessRequest;
import com.krztechnologies.attend.access.service.AccessRequestService;
import com.krztechnologies.attend.access.service.AccessViews;
import com.krztechnologies.attend.access.service.RoleService;
import com.krztechnologies.attend.access.service.UserAccessService;
import com.krztechnologies.attend.common.export.CsvExportService;
import com.krztechnologies.attend.common.export.CsvExportService.Column;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.security.SignedIn;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Settings → Users &amp; roles: users with access, roles, the permission catalogue and access requests. */
@RestController
@RequestMapping("/api/v1")
@Validated
public class AccessController {

    private static final String ROLE_CODE = "^ROLE-[A-Z0-9-]{2,30}$";

    private final UserAccessService users;
    private final RoleService roles;
    private final AccessRequestService requests;

    private final CsvExportService csv;
    private final TimeService time;

    public AccessController(
            UserAccessService users,
            RoleService roles,
            AccessRequestService requests,
            CsvExportService csv,
            TimeService time) {
        this.users = users;
        this.roles = roles;
        this.requests = requests;
        this.csv = csv;
        this.time = time;
    }

    /** @param roleIds role codes (ROLE-…) */
    public record GrantAccessRequest(
            @NotNull UUID employeeId, @NotEmpty List<@Pattern(regexp = ROLE_CODE) String> roleIds) {}

    /** @param roleIds role codes (ROLE-…) */
    public record UpdateRolesRequest(
            @NotEmpty List<@Pattern(regexp = ROLE_CODE) String> roleIds,
            @NotNull Integer version) {}

    public record CreateRoleRequest(
            @NotBlank @Size(max = 60) String name,
            @Size(max = 500) String description,
            @NotEmpty Set<Permission> permissions) {}

    public record UpdateRoleRequest(
            @NotBlank @Size(max = 60) String name,
            @Size(max = 500) String description,
            @NotEmpty Set<Permission> permissions,
            @NotNull Integer version) {}

    /** @param roleIds role codes given to the person */
    public record ApproveRequest(@NotEmpty List<@Pattern(regexp = ROLE_CODE) String> roleIds) {}

    public record DeclineRequest(@NotBlank @Size(max = 500) String reason) {}

    @Operation(summary = "People with access and their roles, paged (sort: user, roles, last, given; default user)")
    @RequiresPermission(Permission.SETTINGS_ROLES)
    @GetMapping("/settings/users")
    public PageResponse<AccessViews.UserView> listUsers(
            @RequestParam(required = false) @Size(max = 100) List<@Pattern(regexp = ROLE_CODE) String> role,
            @RequestParam(required = false) @Size(max = 2) List<@Pattern(regexp = "enabled|disabled") String> account,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return users.list(new UserAccessService.UserFilter(role, account, q), page, size, sort);
    }

    @Operation(summary = "The users table's filter options with their counts over everyone with access")
    @RequiresPermission(Permission.SETTINGS_ROLES)
    @GetMapping("/settings/users/facets")
    public AccessViews.UserFacets userFacets() {
        return users.facets();
    }

    @Operation(summary = "The people with access of the filter as CSV, in the table's order")
    @RequiresPermission({Permission.SETTINGS_ROLES, Permission.EVENTS_EXPORT})
    @GetMapping("/settings/users/export")
    public ResponseEntity<StreamingResponseBody> exportUsers(
            @RequestParam(required = false) @Size(max = 100) List<@Pattern(regexp = ROLE_CODE) String> role,
            @RequestParam(required = false) @Size(max = 2) List<@Pattern(regexp = "enabled|disabled") String> account,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) String sort) {
        DateTimeFormatter iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        DateTimeFormatter day = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
        List<Column<AccessViews.UserView>> columns = List.of(
                new Column<>("User", AccessViews.UserView::displayName),
                new Column<>(
                        "Roles",
                        u -> u.roles().stream().map(AccessViews.RoleRef::name).collect(Collectors.joining("; "))),
                new Column<>(
                        "Last sign-in",
                        u -> u.lastSignInAt() == null
                                ? null
                                : iso.format(time.zoned(u.lastSignInAt()).toOffsetDateTime())),
                new Column<>("Access given", u -> u.addedBy() + " " + day.format(time.zoned(u.addedAt()))));
        return csv.export(
                "users", "Users", columns, users.export(new UserAccessService.UserFilter(role, account, q), sort));
    }

    @Operation(summary = "Give access to a person")
    @RequiresPermission(Permission.SETTINGS_ROLES)
    @PostMapping("/settings/users")
    public ResponseEntity<Void> grant(@Valid @RequestBody GrantAccessRequest request) {
        users.grant(request.employeeId(), request.roleIds());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Change a person's roles (last-administrator and own-role protections apply)")
    @RequiresPermission(Permission.SETTINGS_ROLES)
    @PutMapping("/settings/users/{employeeId}")
    public ResponseEntity<Void> updateRoles(
            @PathVariable UUID employeeId, @Valid @RequestBody UpdateRolesRequest request) {
        users.updateRoles(employeeId, request.roleIds(), request.version());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Remove a person's access; they are signed out at once")
    @RequiresPermission(Permission.SETTINGS_ROLES)
    @DeleteMapping("/settings/users/{employeeId}")
    public ResponseEntity<Void> remove(@PathVariable UUID employeeId) {
        users.remove(employeeId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "All roles with their permissions and number of users")
    @RequiresPermission(Permission.SETTINGS_ROLES)
    @GetMapping("/settings/roles")
    public List<AccessViews.RoleView> listRoles() {
        return roles.list();
    }

    @Operation(summary = "Create a custom role")
    @RequiresPermission(Permission.SETTINGS_ROLES)
    @PostMapping("/settings/roles")
    public AccessViews.RoleView createRole(@Valid @RequestBody CreateRoleRequest request) {
        return roles.create(request.name(), request.description(), request.permissions());
    }

    @Operation(summary = "Edit a role (System Administrator is locked; built-in roles keep their name)")
    @RequiresPermission(Permission.SETTINGS_ROLES)
    @PutMapping("/settings/roles/{code}")
    public AccessViews.RoleView updateRole(
            @PathVariable @Pattern(regexp = ROLE_CODE) String code, @Valid @RequestBody UpdateRoleRequest request) {
        return roles.update(code, request.name(), request.description(), request.permissions(), request.version());
    }

    @Operation(summary = "Delete a custom role; people left without a role lose access")
    @RequiresPermission(Permission.SETTINGS_ROLES)
    @DeleteMapping("/settings/roles/{code}")
    public ResponseEntity<Void> deleteRole(@PathVariable @Pattern(regexp = ROLE_CODE) String code) {
        roles.delete(code);
        return ResponseEntity.noContent().build();
    }

    /** Every signed-in user: the UI names a missing permission ("Needs the … permission") and shows one's own (D-084). */
    @Operation(summary = "The permission catalogue (19 keys with group, label and description)")
    @SignedIn
    @GetMapping("/permissions")
    public List<AccessViews.PermissionView> permissions() {
        return roles.catalogue();
    }

    @Operation(summary = "Access requests, newest first")
    @RequiresPermission(Permission.SETTINGS_ROLES)
    @GetMapping("/settings/access-requests")
    public List<AccessViews.AccessRequestView> listRequests(
            @RequestParam(required = false) AccessRequest.Status status) {
        return requests.list(status);
    }

    @Operation(summary = "Approve an access request with roles")
    @RequiresPermission(Permission.SETTINGS_ROLES)
    @PostMapping("/settings/access-requests/{code}/approve")
    public ResponseEntity<Void> approve(
            @PathVariable @Pattern(regexp = "^AR-\\d{4,}$") String code, @Valid @RequestBody ApproveRequest request) {
        requests.approve(code, request.roleIds());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Decline an access request; the person sees the reason")
    @RequiresPermission(Permission.SETTINGS_ROLES)
    @PostMapping("/settings/access-requests/{code}/decline")
    public ResponseEntity<Void> decline(
            @PathVariable @Pattern(regexp = "^AR-\\d{4,}$") String code, @Valid @RequestBody DeclineRequest request) {
        requests.decline(code, request.reason());
        return ResponseEntity.noContent().build();
    }
}

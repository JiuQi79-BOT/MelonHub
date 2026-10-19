package com.melonhub.client;

/**
 * 用户的当前部门不是请求的部门（HTTP 409，code=DEPARTMENT_MISMATCH）。
 * <p>普通校验不会替用户切换部门；确实要切换时（例如用户在业务系统中主动选择了其他部门），调用
 * {@link MelonHubAuthorizer#switchDepartment}，这会使该用户在原部门所有软件中的上下文失效。</p>
 */
public class DepartmentMismatchException extends MelonHubApiException {

    public DepartmentMismatchException(String code, String message, String requestId) {
        super(409, code, message, requestId);
    }
}

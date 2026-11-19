# Run in the same PowerShell session that will start the course backend.
[CmdletBinding()]
param(
    [switch]$IssueNewKey,
    [switch]$GrantReviewer,
    [string]$ReviewerExternalId = $(if ($env:MOCK_TEACHER_USER_ID) { $env:MOCK_TEACHER_USER_ID } else { 'sso-mock-teacher' })
)

$ErrorActionPreference = 'Stop'
$baseUrl = 'http://127.0.0.1:8080'
$siteCode = 'course-project-mgt'
$headers = @{}

function Invoke-MelonApi([string]$Method, [string]$Path, [hashtable]$Data) {
    $request = @{ Method = $Method; Uri = $baseUrl + $Path; Headers = $headers; TimeoutSec = 10 }
    if ($null -ne $Data) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = ConvertTo-Json -InputObject $Data -Depth 5 -Compress
    }
    Invoke-RestMethod @request
}

try {
    Invoke-MelonApi GET '/api/health' | Out-Null
} catch {
    throw "MelonHub 在 $baseUrl 不可用。请先用项目配置启动 MelonHub。"
}

if ($env:MELONHUB_ADMIN_TOKEN) {
    $token = $env:MELONHUB_ADMIN_TOKEN
} else {
    . (Join-Path $PSScriptRoot '../../scripts/service-tools.ps1')
    $config = Read-MelonConfiguration
    $login = Invoke-MelonApi POST '/api/auth/login' @{
        username = if ($config.ADMIN_USERNAME) { $config.ADMIN_USERNAME } else { 'admin' }
        password = $config.ADMIN_PASSWORD
    }
    $token = $login.token
}
$headers.Authorization = "Bearer $token"

$app = (Invoke-MelonApi GET '/api/admin/applications') | Where-Object code -CEQ $siteCode
if (-not $app) {
    $app = Invoke-MelonApi POST '/api/admin/applications' @{
        code = $siteCode; name = '课程项目管理系统'; description = '课程系统权限接入'
    }
    $env:MELONHUB_API_KEY = $app.apiKey
} elseif ($IssueNewKey) {
    $key = Invoke-MelonApi POST "/api/admin/applications/$($app.id)/keys" @{ name = '课程系统联调' }
    $env:MELONHUB_API_KEY = $key.apiKey
}

$department = (Invoke-MelonApi GET '/api/admin/departments') | Where-Object code -CEQ $siteCode
if (-not $department) {
    $department = Invoke-MelonApi POST '/api/admin/departments' @{ code = $siteCode; name = '课程项目管理' }
}
$domain = "departmentId=$($department.id)&applicationId=$($app.id)"

$permission = (Invoke-MelonApi GET "/api/admin/permissions?$domain") | Where-Object code -CEQ 'CR'
if (-not $permission) {
    $permission = Invoke-MelonApi POST "/api/admin/permissions?$domain" @{
        code = 'CR'; name = '课程审核'; resource = 'course'; action = 'review'
    }
}

$role = (Invoke-MelonApi GET "/api/admin/roles?$domain") | Where-Object code -CEQ 'course-reviewer'
if (-not $role) {
    $role = Invoke-MelonApi POST "/api/admin/roles?$domain" @{
        code = 'course-reviewer'; name = '课程审核员'; description = '课程管理菜单及审核操作'
    }
}
if ($permission.id -notin $role.permissionIds) {
    $grants = @($role.permissionIds | Where-Object { $null -ne $_ }) + @($permission.id)
    Invoke-MelonApi PUT "/api/admin/roles/$($role.id)/permissions?$domain" @{ ids = $grants } | Out-Null
}

if ($GrantReviewer) {
    $keyword = [uri]::EscapeDataString($ReviewerExternalId)
    $page = Invoke-MelonApi GET "/api/admin/users?keyword=$keyword&size=200"
    if ($page.total -gt $page.items.Count) { throw '用户搜索结果超过一页。请在控制台按外部标识确认账号。' }
    $reviewer = $page.items | Where-Object externalId -CEQ $ReviewerExternalId
    if (-not $reviewer) {
        $reviewer = Invoke-MelonApi POST '/api/admin/users' @{
            externalId = $ReviewerExternalId; name = '课程联调审核员'; departmentIds = @($department.id)
        }
    } elseif ($department.id -notin $reviewer.departmentIds) {
        Invoke-MelonApi PUT "/api/admin/users/$($reviewer.id)" @{
            externalId = $reviewer.externalId; name = $reviewer.name; email = $reviewer.email
            departmentIds = @($reviewer.departmentIds) + @($department.id)
        } | Out-Null
    }
    Invoke-MelonApi POST "/api/admin/assignments/batch?$domain" @{
        userIds = @($reviewer.id); roleIds = @($role.id)
    } | Out-Null
}

if (-not $env:MELONHUB_API_KEY) {
    throw '应用及审核权限已配置。请设置已有的 MELONHUB_API_KEY，或指定 -IssueNewKey 签发一个新密钥。'
}
$headers = @{ 'X-API-Key' = $env:MELONHUB_API_KEY }
$visibleRoles = Invoke-MelonApi GET "/api/v1/departments/$($department.id)/roles"
if ($role.id -notin $visibleRoles.id) { throw '当前 API Key 不属于课程系统应用。' }

$env:MELONHUB_BASE_URL = $baseUrl
$env:MELONHUB_DEPARTMENT_ID = [string]$department.id
$env:MELONHUB_ENABLED = 'true'
Write-Output "课程系统接入已配置。应用 ID $($app.id)，部门 ID $($department.id)，审核角色 ID $($role.id)。"

"""Rebuild the current MelonHub diagrams with Pillow and a CJK font."""

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


OUT = Path(__file__).resolve().parent
FONT = Path("C:/Windows/Fonts/msyh.ttc")
INK = "#172923"
ACCENT = "#176b53"
MUTED = "#52645c"
FILL = "#f1f7f3"


class Diagram:
    def __init__(self, title, subtitle, height=1600):
        self.image = Image.new("RGB", (2400, height), "white")
        self.draw = ImageDraw.Draw(self.image)
        self.fonts = {size: ImageFont.truetype(str(FONT), size) for size in (23, 25, 28, 34, 46)}
        self.text((70, 32), title, 46)
        self.text((70, 104), subtitle, 25, MUTED)

    def text(self, xy, value, size=25, color=INK):
        self.draw.text(xy, value, font=self.fonts[size], fill=color)

    def box(self, x, y, width, title, lines, height=205):
        self.draw.rectangle((x, y, x + width, y + height), fill="white", outline=MUTED, width=3)
        self.draw.rectangle((x + 2, y + 2, x + width - 2, y + 58), fill=FILL)
        self.text((x + 18, y + 12), title, 28, ACCENT)
        for i, line in enumerate(lines):
            bounds = self.draw.textbbox((0, 0), line, font=self.fonts[23])
            if bounds[2] - bounds[0] > width - 36 or 78 + i * 36 + 28 > height:
                raise ValueError(f"Text exceeds box: {title}: {line}")
            self.text((x + 18, y + 76 + i * 36), line, 23)

    def arrow(self, points, label=None, label_xy=None):
        self.draw.line(points, fill=ACCENT, width=4)
        start, end = points[-2:]
        angle = math.atan2(end[1] - start[1], end[0] - start[0])
        head = [end] + [
            (end[0] - 17 * math.cos(angle + delta), end[1] - 17 * math.sin(angle + delta))
            for delta in (-0.45, 0.45)
        ]
        self.draw.polygon(head, fill=ACCENT)
        if label:
            self.text(label_xy, label, 23, ACCENT)

    def save(self, name):
        self.image.save(OUT / name)
        print(f"Rendered {name}: {self.image.width}x{self.image.height}")


def classes():
    d = Diagram("MelonHub 类图与核心数据关系", "V7 软件共享权限 | 2026-10-27 | 上半部分为实现类依赖，下半部分为数据关系概览")
    d.box(70, 180, 520, "ApiGuard / AdminAccess", ["统一认证、路径校验、限流", "管理员角色与软件范围检查"])
    d.box(650, 180, 520, "BusinessController", ["/api/v1：API Key 固定软件", "上下文、校验及软件授权管理"])
    d.box(1230, 180, 1100, "Admin / Governance / Auth / AccountController", ["/api/admin：基础配置、审批、复核、审计", "/api/auth：管理员登录、改密、MFA"])
    d.box(70, 490, 520, "AuthService", ["login() / admin() / application()", "changePassword() / confirmMfa()"])
    d.box(650, 490, 520, "ContextService", ["issue() / effective()", "check() / revoke()"])
    d.box(1230, 490, 520, "RbacService", ["role() / permission()", "rolePermissions() / userRoles()"])
    d.box(1810, 490, 520, "GovernanceService", ["submit() / approve() / closeReview()", "importManifest() / permissionHolders()"])
    d.arrow([(330, 385), (330, 490)], "认证", (348, 421))
    d.arrow([(910, 385), (910, 490)], "上下文", (928, 421))
    d.arrow([(1780, 385), (1780, 432), (1490, 432), (1490, 490)])
    d.arrow([(1780, 432), (2070, 432), (2070, 490)])
    for x in (330, 910, 1490, 2070):
        d.arrow([(x, 695), (x, 810)])
    d.box(70, 810, 2260, "DAO 数据访问层 / Spring JDBC", [
        "AuthDao | ContextDao | RbacDao | GovernanceDao | WebhookDao | AuditDao",
        "负责参数绑定、SQL、数据库锁与条件更新；MySQL 为唯一持久数据源。",
    ], 180)
    d.text((70, 1040), "核心数据关系（表关系概览，不代表 Java Entity 类）", 34)
    d.box(70, 1140, 440, "users 用户", ["external_id：业务用户标识", "不保存业务用户登录密码"], 185)
    d.box(970, 1140, 450, "roles 软件角色", ["application_id：所属软件", "软件内角色编码唯一"], 185)
    d.box(1870, 1140, 460, "permissions 共享权限", ["application_id：所属软件", "软件内权限编码唯一"], 185)
    d.arrow([(510, 1230), (970, 1230)], "user_roles：多对多分配", (547, 1170))
    d.arrow([(1420, 1230), (1870, 1230)], "role_permissions：多对多", (1443, 1170))
    d.text((70, 1345), "服务在事务内协调 DAO、可选 PermissionCache、WebhookService 与审计记录。", 25, MUTED)
    d.text((70, 1390), "departments 与 users 通过 memberships 多对多关联，仅用于成员管理和筛选。", 25)
    d.text((70, 1440), "用户分配包含期限与 grant_id；角色和权限按软件隔离；修改共享定义影响所有引用角色。", 25)
    d.text((70, 1510), "管理员账号/会话与业务用户/权限上下文是两套独立数据；平台不代替业务系统登录。", 25, MUTED)
    d.save("类图.png")


def data_flow():
    d = Diagram("MelonHub 数据流图", "当前实现 | 2026-10-27 | 部门仅管理成员，软件隔离授权；业务身份由接入系统认证", 1740)
    rows = [
        ("管理员 / 管理控制台", ["Bearer 管理员会话", "用户资料与部门成员变更"],
         "P1 用户与组织管理", ["管理用户 externalId 与部门成员", "支持逐行用户导入和结果反馈", "部门变化不撤销软件授权或上下文"],
         "D1 用户与组织数据", ["users / departments / memberships", "admin_accounts / admin_sessions"]),
        ("管理员 / 业务软件后端", ["管理员会话 / 软件 API Key", "本软件角色、权限和关联配置"],
         "P2 软件授权配置", ["创建共享权限并由多个角色引用", "分配角色和期限，批量追加保留有效分配", "PUT 关联接口整体替换目标集合"],
         "D2 软件授权数据", ["applications / roles / permissions", "role_permissions / user_roles"]),
        ("已认证的业务软件后端", ["API Key + externalId 创建上下文", "API Key + token 提交权限检查"],
         "P3 上下文与在线权限校验", ["创建、查询、批量检查与注销上下文", "校验激活、启用且未到期的角色权限", "返回 allowed / decisions，不执行业务操作"],
         "D3 上下文与授权状态", ["permission_contexts / context_roles", "读取用户、软件与当前有效分配"]),
        ("业务后端 / 有权限的管理员", ["员工申请角色，管理员批准或驳回", "按软件发起复核并决定回收"],
         "P4 授权治理", ["允许无部门用户申请、获批", "批准更新分配，复核按快照 grant_id 回收", "保存名称快照、期限和处理历史"],
         "D4 申请与复核数据", ["access_requests / access_reviews", "access_review_items"]),
        ("管理员 / Webhook 接收方", ["管理订阅、查看审计与系统状态", "接收方验签后处理变更事件"],
         "P5 通知、审计与运维", ["事务外盒、HMAC 签名、并行投递与重试", "审计变更、封存并验证哈希链", "查询与权限校验不逐次写入审计"],
         "D5 通知与审计数据", ["webhooks / webhook_deliveries", "audit_events / audit_seal"]),
    ]
    for i, (actor, actor_lines, process, process_lines, data, data_lines) in enumerate(rows):
        y = 180 + i * 280
        d.box(70, y, 550, actor, actor_lines, 215)
        d.box(770, y, 840, process, process_lines, 215)
        d.box(1760, y, 570, data, data_lines, 215)
        d.arrow([(620, y + 100), (770, y + 100)], "配置 / 回执" if i == 4 else "请求", (633, y + 62))
        d.arrow([(770, y + 170), (620, y + 170)], "查询 / 事件" if i == 4 else "结果", (633, y + 174))
        d.arrow([(1610, y + 100), (1760, y + 100)], "写 / 查", (1630, y + 62))
        d.arrow([(1760, y + 170), (1610, y + 170)], "数据", (1633, y + 174))
    d.text((70, 1610), "所有 D1-D5 数据均在云 MySQL；Redis 是可选缓存和限流器，当前默认关闭。", 25)
    d.text((70, 1660), "当前部署：本机 Vite :5174 → 本机 SSH 通道 :8080 → 服务器回环 :18080 → 后端容器 :8080。", 25, MUTED)
    d.save("数据流图.png")


def communication():
    d = Diagram("MelonHub 通信图：业务后端在线检查权限", "当前实现 | 2026-10-27 | 数字表示调用顺序；上下文失效时重新创建，不按部门切换授权", 1550)
    d.box(70, 210, 550, "caller : 可信业务后端", ["先完成业务用户登录", "保存软件 API Key 与上下文 token", "allowed=true 才执行业务操作"], 235)
    d.box(870, 210, 570, "guard : ApiGuard", ["验证 API Key，确定软件", "规范路径检查与按软件限流", "不允许参数覆盖所属软件"], 235)
    d.box(1710, 210, 620, "api : BusinessController", ["POST /api/v1/contexts", "POST /api/v1/contexts/check", "DTO 绑定与请求参数校验"], 235)
    d.arrow([(620, 300), (870, 300)], "1：发送请求", (634, 246))
    d.arrow([(1440, 300), (1710, 300)], "2：认证后分发", (1460, 246))
    d.box(1710, 700, 620, "service : ContextService", ["创建时检查用户和软件状态", "校验时计算有效角色权限并集", "返回 allowed、decisions、版本"], 235)
    d.box(870, 700, 570, "dao : ContextDao", ["issue：锁用户并创建上下文", "check：未命中缓存时读取快照", "按软件、角色状态及期限查询"], 235)
    d.box(70, 700, 550, "db : 云 MySQL", ["上下文、激活角色、软件与用户", "角色分配期限及共享权限关联", "管理员会话与业务上下文独立"], 235)
    d.arrow([(2020, 445), (2020, 700)], "3：委托业务处理", (2045, 542))
    d.arrow([(1710, 810), (1440, 810)], "4：DAO 调用", (1450, 755))
    d.arrow([(870, 810), (620, 810)], "5：执行 SQL", (636, 755))
    d.arrow([(2020, 935), (2020, 1030), (35, 1030), (35, 540), (345, 540), (345, 445)])
    d.text((540, 982), "6：结果经 Controller / ApiGuard 返回业务后端；无权限返回 allowed=false", 23, ACCENT)
    d.box(1390, 1150, 940, "cache : PermissionCache（可选）", ["校验可命中缓存；授权变更使用代标记失效协议", "缓存不得越过角色分配到期时刻；故障回退 MySQL", "当前 CACHE_ENABLED=false，关闭时直接走数据库"], 215)
    d.arrow([(2190, 935), (2380, 935), (2380, 1250), (2330, 1250)])
    d.text((70, 1160), "请求区别", 34)
    d.text((70, 1220), "创建：X-API-Key + externalId，无需已有上下文 token。", 25)
    d.text((70, 1270), "校验：X-API-Key + X-Context-Token + permissions 数组。", 25)
    d.text((70, 1390), "权限变化后失效或重新计算；移出部门不撤销软件角色和已有上下文。", 25)
    d.text((70, 1450), "平台只给出授权结果，不代替业务用户登录，也不撤回已执行的外部业务操作。", 25, MUTED)
    d.save("通信图.png")


if __name__ == "__main__":
    if not FONT.exists():
        raise SystemExit("Set FONT to an installed CJK TrueType font before rendering.")
    classes()
    data_flow()
    communication()

// Prepare the walkthrough through the running API, using the ignored local credentials.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '..');
const requestOnly = process.argv.includes('--request');
const verifyOnly = process.argv.includes('--verify');
if (process.argv.slice(2).some(arg => !['--request', '--verify'].includes(arg)) || (requestOnly && verifyOnly)) {
  console.error('用法：node scripts/prepare-demo.cjs [--request | --verify]');
  process.exit(1);
}
const config = Object.fromEntries(fs.readFileSync(path.join(root, '.env'), 'utf8')
  .split(/\r?\n/).filter(line => /^[A-Z][A-Z0-9_]*=/.test(line))
  .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
const base = 'http://127.0.0.1:8080/api';
let token;
const temporaryKeys = [];
const contexts = [];

async function call(method, route, body, headers) {
  const response = await fetch(base + route, {
    method, headers: { 'Content-Type': 'application/json',
      ...(headers ?? (token ? { Authorization: 'Bearer ' + token } : {})) },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000),
  });
  if (!response.ok) throw Error(`${method} ${route.split('?')[0]}：HTTP ${response.status}，请检查登录、运行状态或演示配置。`);
  return response.json();
}

async function ensure(route, rows, key, body) {
  const found = rows.find(row => row[key] === body[key]);
  if (found) {
    if (found.name !== body.name) throw Error(`演示编码 ${body[key]} 已被其他名称使用，已停止。`);
    return found;
  }
  if (requestOnly || verifyOnly) throw Error(`缺少 ${body.name}，请先运行不带参数的准备命令。`);
  const created = await call('POST', route, body);
  if (created.keyId) temporaryKeys.push({app:created.id,id:created.keyId});
  const row = {...body, id:created.id, created:true};
  rows.push(row);
  console.log('已创建：' + body.name);
  return row;
}

async function main() {
  const login = await call('POST', '/auth/login', {username:config.ADMIN_USERNAME,password:config.ADMIN_PASSWORD}, {});
  token = login.token;
  try {
    const me = await call('GET', '/auth/me');
    if (!['SUPER_ADMIN','ADMIN'].includes(me.role)) throw Error('请使用超级管理员或平台管理员准备演示。');
    const departments = await call('GET','/admin/departments');
    const rd = await ensure('/admin/departments',departments,'code',{name:'演示·研发部',code:'demo-rd'});
    const finance = await ensure('/admin/departments',departments,'code',{name:'演示·财务部',code:'demo-finance'});
    const applications = await call('GET','/admin/applications');
    const project = await ensure('/admin/applications',applications,'code',{
      name:'演示·项目协作',code:'demo-project',owner:'演示负责人',description:'使用说明书配套演示软件，可用于授权、审批和复核练习。',
    });
    const expense = await ensure('/admin/applications',applications,'code',{
      name:'演示·费用报销',code:'demo-expense',owner:'演示负责人',description:'演示同一用户在不同软件拥有独立角色。',
    });
    const userRows = [];
    for (let page = 1; ; page++) {
      const result = await call('GET',`/admin/users?page=${page}&size=200`);
      userRows.push(...result.items);
      if (userRows.length >= result.total) break;
    }
    const users = {};
    for (const [key,name,departmentIds] of [
      ['zhangsan','张三',[rd.id]],['lisi','李四',[rd.id]],['wangwu','王五',[finance.id]],['zhaoliu','赵六',[]],
    ]) users[key] = await ensure('/admin/users',userRows,'externalId',{
      name:'演示·'+name,externalId:'demo-'+key,email:'',departmentIds,
    });

    async function configure(app, permissionSpecs, roleSpecs) {
      const query = '?applicationId='+app.id;
      const permissions = await call('GET','/admin/permissions'+query);
      const roles = await call('GET','/admin/roles'+query);
      const permissionIds = {}, roleIds = {};
      for (const [code,name,action] of permissionSpecs) {
        const item = await ensure('/admin/permissions'+query,permissions,'code',{code,name,resource:code.split(':')[0],action});
        permissionIds[code] = item.id;
      }
      for (const [code,name,codes] of roleSpecs) {
        const role = await ensure('/admin/roles'+query,roles,'code',{code,name,description:'使用说明书配套演示角色'});
        roleIds[code] = role.id;
        if (role.created) await call('PUT',`/admin/roles/${role.id}/permissions`+query,{ids:codes.map(code=>permissionIds[code])});
      }
      return roleIds;
    }
    const projectRoles = await configure(project,[
      ['project:read','查看项目','read'],['project:create','创建项目','create'],['project:approve','审批项目','approve'],
    ],[
      ['reader','项目查看员',['project:read']],
      ['editor','项目创建员',['project:read','project:create']],
      ['approver','项目审批员',['project:read','project:approve']],
    ]);
    const expenseRoles = await configure(expense,[['expense:create','提交报销','create']],
      [['submitter','报销申请人',['expense:create']]]);

    if (!requestOnly && !verifyOnly) {
      for (const [app,role,userIds] of [
        [project,projectRoles.editor,[users.zhangsan.id]],
        [project,projectRoles.reader,[users.lisi.id,users.zhaoliu.id]],
        [project,projectRoles.approver,[users.wangwu.id]],
        [expense,expenseRoles.submitter,[users.zhangsan.id]],
      ]) await call('POST','/admin/assignments/batch?applicationId='+app.id,{userIds,roleIds:[role]});
      console.log('基准角色已补齐；已有有效授权、期限和修改过的角色权限保留。');
    }

    if (!verifyOnly) {
      const pending = await call('GET',`/admin/access-requests?applicationId=${project.id}&status=PENDING&keyword=demo-lisi&page=1&size=200`);
      if (!pending.items.some(row=>row.userId===users.lisi.id && row.roleId===projectRoles.editor)) {
        const assigned = await call('GET',`/admin/users/${users.lisi.id}/roles?applicationId=${project.id}`);
        if (assigned.roleIds.includes(projectRoles.editor)) {
          console.log('李四已拥有项目创建员：如需重演审批，先在成员授权中仅保留项目查看员，再运行 --request。');
        } else {
          const key = await call('POST',`/admin/applications/${project.id}/keys`,{name:'演示准备临时密钥',expiresInDays:1});
          temporaryKeys.push({app:project.id,id:key.id});
          await call('POST','/v1/access-requests',{
            externalId:users.lisi.externalId,departmentId:rd.id,roleId:projectRoles.editor,
            reason:'演示：李四需要创建项目，请授予 7 天项目创建员角色',durationDays:7,
          },{'X-API-Key':key.apiKey});
          console.log('已准备：李四申请项目创建员（待审批，7 天）。');
        }
      } else console.log('已有李四的待审批申请，已保留。');
    }

    const checks = [];
    for (const [name,codes] of [
      ['zhangsan',['project:read','project:create','project:approve']],
      ['lisi',['project:read','project:create']],['wangwu',['project:read','project:approve']],
      ['zhaoliu',['project:read','project:create']],
    ]) {
      const result = await call('POST','/admin/simulate',{
        userId:users[name].id,departmentId:0,applicationId:project.id,permissions:codes,
      });
      checks.push({externalId:users[name].externalId,eligible:result.eligible,decisions:result.decisions});
    }
    assert(checks.every(result=>result.eligible), '演示用户应能创建上下文，请检查是否停用了用户或软件。');
    // Exercise the real business API with a short-lived key; all credentials stay in memory.
    const key = await call('POST',`/admin/applications/${project.id}/keys`,{name:'演示验证临时密钥',expiresInDays:1});
    temporaryKeys.push({app:project.id,id:key.id});
    for (const check of checks) {
      const issued = await call('POST','/v1/contexts',{externalId:check.externalId},{'X-API-Key':key.apiKey});
      const headers = {'X-API-Key':key.apiKey,'X-Context-Token':issued.contextToken};
      contexts.push(headers);
      const actual = await call('POST','/v1/contexts/check',{permissions:Object.keys(check.decisions)},headers);
      assert.deepEqual(actual.decisions,check.decisions,'在线校验应与诊断一致');
    }
    console.log('诊断与真实业务校验一致（true=允许，false=拒绝）：');
    for (const check of checks) console.log(JSON.stringify(check));
    console.log(verifyOnly ? '验证完成，当前结果见上方；未重置演示进度。' : '准备完成。刷新控制台，按 docs/使用说明书.md 演示；脚本不会重置已有演示进度。');
  } finally {
    let cleanupFailed = false;
    for (const headers of contexts) {
      try { await call('DELETE','/v1/contexts',undefined,headers); }
      catch { cleanupFailed = true; console.error('临时上下文注销失败，请在演示用户菜单中强制下线。'); }
    }
    for (const key of temporaryKeys) {
      try { await call('DELETE',`/admin/applications/${key.app}/keys/${key.id}`); }
      catch { cleanupFailed = true; console.error(`临时密钥清理失败：请在软件 #${key.app} 吊销密钥 #${key.id}。`); }
    }
    try { await call('POST','/auth/logout'); }
    catch { cleanupFailed = true; console.error('准备工具注销失败，请通过管理员账号结束该账号的其他会话。'); }
    if (cleanupFailed) process.exitCode = 1;
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });

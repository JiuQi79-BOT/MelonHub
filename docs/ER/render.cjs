// Render schema-only snapshots. No connection information or business rows are needed.
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '../..');
const dependency = createRequire(path.join(root, '.local/er-tools/package.json'));
const { instance } = dependency('@viz-js/viz');
const { Resvg } = dependency('@resvg/resvg-js');
const input = process.argv[2] || path.join(__dirname, 'schema.json');
const schema = JSON.parse(fs.readFileSync(input, 'utf8'));
const tables = [...new Set(schema.columns.map(c => c.TABLE_NAME))];
const titles = {
  departments:'部门', applications:'业务软件', users:'业务用户', memberships:'部门成员',
  roles:'软件角色', permissions:'软件共享权限', role_permissions:'角色权限关联',
  user_roles:'用户角色分配', permission_contexts:'权限上下文', context_roles:'上下文激活角色',
  admin_accounts:'控制台管理员', admin_sessions:'管理员会话',
  admin_account_applications:'管理员软件范围', application_keys:'软件 API Key',
  access_requests:'角色申请', access_reviews:'权限复核', access_review_items:'复核条目',
  webhooks:'Webhook 订阅', webhook_deliveries:'通知投递外盒',
  audit_events:'审计事件', audit_seal:'审计链封存状态',
  role_migration_archive:'V6 角色历史快照', permission_migration_archive:'V6 未关联权限归档',
  permission_merge_archive:'V7 权限合并归档', flyway_schema_history:'Flyway 迁移记录',
};
const business = {
  departments:['name','code'], applications:['name','code','enabled','rate_limit'],
  users:['external_id','name','enabled','context_version'],
  roles:['name','code','enabled'], permissions:['name','code','resource','action'],
  user_roles:['grant_id','expires_at','granted_by'], permission_contexts:['context_version','expires_at'],
  admin_accounts:['role','enabled','credential_version'], admin_sessions:['credential_version','expires_at'],
  application_keys:['key_hash','name','expires_at','revoked_at'],
  access_requests:['status','pending_role_id','duration_days','user_name','role_name','grant_expires_at'],
  access_reviews:['name','status','department_name','due_at'],
  access_review_items:['department_id','grant_id','decision','applied','skipped','user_name','role_name'],
  webhooks:['name','events','enabled'],webhook_deliveries:['event_id','event_type','status','attempts','next_attempt_at'],
  audit_events:['department_id','application_id','actor','action','seal_seq','prev_hash','chain_hash'],
  audit_seal:['last_id','last_seq','last_hash','anchor_seq','anchor_hash','v2_from_seq'],
  role_migration_archive:['id','department_id','application_id','name','code'],
  permission_migration_archive:['id','department_id','application_id','code'],
  permission_merge_archive:['id','application_id','role_id','code','canonical_id'],
  flyway_schema_history:['version','description','success'],
};
const groups = {
  core:['departments','memberships','users','user_roles','roles','applications','role_permissions','permissions','permission_contexts','context_roles'],
  governance:['access_requests','access_reviews','access_review_items'],
  security:['application_keys','admin_accounts','admin_sessions','admin_account_applications'],
  events:['webhooks','webhook_deliveries','audit_events','audit_seal'],
  archives:['role_migration_archive','permission_migration_archive','permission_merge_archive','flyway_schema_history'],
};
const colors = {core:'#e9f5f0',governance:'#fff1da',security:'#eaf1fb',events:'#fcebef',archives:'#f0f1f2'};
const groupOf = table => Object.keys(groups).find(key=>groups[key].includes(table));
const escape = value => String(value).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
const constraints = table => {
  const grouped = new Map();
  for (const key of schema.keys.filter(key=>key.TABLE_NAME===table)) {
    if (!grouped.has(key.CONSTRAINT_NAME)) grouped.set(key.CONSTRAINT_NAME,[]);
    grouped.get(key.CONSTRAINT_NAME).push(key);
  }
  return [...grouped.values()];
};

function entity(table) {
  const keys = constraints(table);
  const primary = keys.find(rows=>rows[0].CONSTRAINT_NAME==='PRIMARY') || [];
  const unique = keys.filter(rows=>!rows[0].REFERENCED_TABLE_NAME && rows[0].CONSTRAINT_NAME!=='PRIMARY');
  const pk = primary.map(row=>row.COLUMN_NAME);
  const fk = keys.filter(rows=>rows[0].REFERENCED_TABLE_NAME).flatMap(rows=>rows.map(row=>row.COLUMN_NAME));
  const oneColumnUnique = unique.filter(rows=>rows.length===1).map(rows=>rows[0].COLUMN_NAME);
  const selected = new Set([...pk,...fk,...oneColumnUnique,...(business[table] || [])]);
  const rows = schema.columns.filter(column=>column.TABLE_NAME===table && selected.has(column.COLUMN_NAME));
  const body = rows.map(column=>{
    const flags = [pk.includes(column.COLUMN_NAME) && 'PK',fk.includes(column.COLUMN_NAME) && 'FK',oneColumnUnique.includes(column.COLUMN_NAME) && 'UK'].filter(Boolean).join(',');
    return `<TR><TD WIDTH="48" ALIGN="LEFT"><FONT COLOR="#57636b">${flags || '&#160;'}</FONT></TD><TD WIDTH="210" PORT="${column.COLUMN_NAME}" ALIGN="LEFT">${column.COLUMN_NAME}${column.IS_NULLABLE==='YES'?' ?':''}</TD><TD WIDTH="120" ALIGN="LEFT"><FONT COLOR="#57636b">${escape(column.COLUMN_TYPE)}</FONT></TD></TR>`;
  });
  const footer = [];
  if (pk.length>1) footer.push('PK ('+pk.join(', ')+')');
  for (const key of unique.filter(rows=>rows.length>1)) footer.push('UK ('+key.map(row=>row.COLUMN_NAME).join(', ')+')');
  if (table.includes('archive')) footer.push('历史快照：无主键、无外键');
  if (table==='audit_seal') footer.push('单行状态 id=1；无审计事件外键');
  if (table==='access_review_items') footer.push('department_id / grant_id 不是外键');
  if (table==='users') footer.push('active_department_id 为历史兼容字段');
  if (table==='permissions') footer.push('当前无 role_id / department_id');
  for (const text of footer) body.push(`<TR><TD COLSPAN="3" ALIGN="LEFT" BGCOLOR="#fafbfc"><FONT POINT-SIZE="10" COLOR="#57636b">${escape(text)}</FONT></TD></TR>`);
  return `${table} [label=<<TABLE WIDTH="440" BORDER="1" COLOR="#76828a" CELLBORDER="0" CELLSPACING="0" CELLPADDING="6"><TR><TD COLSPAN="3" BGCOLOR="${colors[groupOf(table)]}"><B>${table}</B><BR/><FONT POINT-SIZE="12">${titles[table]}</FONT></TD></TR>${body.join('')}</TABLE>>];`;
}

function relation(key) {
  const columns = schema.keys.filter(row=>row.TABLE_NAME===key.TABLE_NAME && row.CONSTRAINT_NAME===key.CONSTRAINT_NAME);
  const optional = columns.some(row=>schema.columns.find(c=>c.TABLE_NAME===row.TABLE_NAME && c.COLUMN_NAME===row.COLUMN_NAME).IS_NULLABLE==='YES');
  const unique = constraints(key.TABLE_NAME).filter(rows=>!rows[0].REFERENCED_TABLE_NAME);
  const childIsUnique = unique.some(rows=>rows.every(row=>columns.some(c=>c.COLUMN_NAME===row.COLUMN_NAME)));
  const caption = columns.map(row=>row.COLUMN_NAME).join(' + ');
  return `${key.REFERENCED_TABLE_NAME} -> ${key.TABLE_NAME} [taillabel="${optional?'0..1':'1'}", headlabel="${childIsUnique?'0..1':'0..N'}", label="${caption}", tooltip="${key.CONSTRAINT_NAME}: ON DELETE ${key.DELETE_RULE}"];`;
}

function dot(selected, title, clustered=false) {
  const nodes = new Set(selected);
  const lines = ['digraph MelonHub {',
    'graph [rankdir=LR, bgcolor="white", pad="0.4", nodesep="0.55", ranksep="1.35", splines=polyline, fontname="Arial", fontsize=18, labelloc=t, labeljust=l, outputorder=edgesfirst];',
    'node [shape=plain, fontname="Arial", fontsize=12];',
    'edge [dir=none, color="#7b8991", fontcolor="#52616a", fontname="Arial", fontsize=10, labeldistance=1.8, labelangle=25];',
    'label='+JSON.stringify(title+'\nFlyway V7 | 2026-10-28 | PK 主键 · FK 外键 · UK 唯一约束 · ? 可空\n只列关键字段；实线均为真实外键，线两端数字为关联基数')+';'];
  if (clustered) {
    const names={core:'01 组织与软件授权',governance:'02 申请与复核',security:'03 管理员与密钥',events:'04 通知与审计',archives:'05 历史归档与迁移管理'};
    for (const [key, values] of Object.entries(groups)) {
      lines.push(`subgraph cluster_${key} { label="${names[key]}"; color="#dce2e5"; fontsize=16;`);
      values.filter(table=>nodes.has(table)).forEach(table=>lines.push(entity(table)));
      if (key==='archives') lines.push(values.join(' -> ')+' [style=invis, weight=60];');
      lines.push('}');
    }
  } else selected.forEach(table=>lines.push(entity(table)));
  schema.foreignKeys.filter(key=>nodes.has(key.TABLE_NAME)&&nodes.has(key.REFERENCED_TABLE_NAME)).forEach(key=>lines.push(relation(key)));
  lines.push('}');
  return lines.join('\n');
}

async function main() {
  if (tables.length!==25 || schema.foreignKeys.length!==30) throw Error('Expected verified V7 snapshot: 25 tables, 30 FKs. Review schema changes first.');
  if (tables.some(table=>!titles[table])) throw Error('A table is missing its description');
  const viz = await instance();
  const diagrams = [
    ['核心授权',groups.core,'MelonHub E-R：组织、软件授权与上下文'],
    ['申请复核',['applications','departments','users','roles',...groups.governance],'MelonHub E-R：角色申请与权限复核'],
    ['管理员密钥',['applications',...groups.security],'MelonHub E-R：管理员、会话与软件密钥'],
    ['通知审计',['applications',...groups.events],'MelonHub E-R：Webhook 投递与审计链'],
    ['全表',tables,'MelonHub 数据库 E-R 全图（25 张表 / 30 条外键）',true],
  ];
  fs.writeFileSync(path.join(__dirname,'schema.json'),JSON.stringify({columns:schema.columns,keys:schema.keys,foreignKeys:schema.foreignKeys},null,2)+'\n');
  for (const [name,selected,title,clustered] of diagrams) {
    const source=dot(selected,title,clustered);
    const prefix=path.join(__dirname,name);
    fs.writeFileSync(prefix+'.dot',source+'\n');
    const result=viz.render(source,{format:'svg',engine:'dot'});
    if (result.status!=='success' || result.errors.some(error=>error.level==='error')) throw Error(JSON.stringify(result.errors));
    const svg=result.output;
    fs.writeFileSync(prefix+'.svg',svg);
    const png=new Resvg(svg,{font:{loadSystemFonts:true,defaultFontFamily:'Microsoft YaHei'},fitTo:{mode:'zoom',value:1.5},background:'white'}).render();
    fs.writeFileSync(prefix+'.png',png.asPng());
    console.log(`${name}: ${selected.length} tables, ${png.width}x${png.height}`);
  }
}
main().catch(error=>{console.error(error.message);process.exitCode=1;});

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { api } from './api'

export interface Department {id:number;name:string;code:string;memberCount:number}
export interface Application {id:number;name:string;code:string;description:string;enabled:boolean;roleCount:number;permissionCount:number;createdAt:string}
export interface User {id:number;name:string;externalId:string;email:string;enabled:boolean;departmentIds:number[];activeDepartmentId:number|null;contextVersion:number}
export interface Role {id:number;name:string;code:string;description:string;enabled:boolean;memberCount:number;permissionIds:number[]}
export interface Permission {id:number;name:string;code:string;resource:string;action:string;roleCount:number}
export interface Audit {id:number;actor:string;action:string;target:string;createdAt:string;departmentName:string|null;applicationName:string|null}
export interface Overview {applications:number;departments:number;users:number;roles:number;permissions:number;members:number;activeContexts:number}
export const useHub = defineStore('hub',()=>{
  const departments=ref<Department[]>([]), applications=ref<Application[]>([]), users=ref<User[]>([]), roles=ref<Role[]>([]), permissions=ref<Permission[]>([]), audits=ref<Audit[]>([])
  const overview=ref<Overview>({applications:0,departments:0,users:0,roles:0,permissions:0,members:0,activeContexts:0})
  const departmentId=ref(Number(localStorage.getItem('melonhub-department'))||0), applicationId=ref(Number(localStorage.getItem('melonhub-application'))||0)
  const busy=ref(false),health=ref(false),toast=ref(''),toastError=ref(false),lastUpdated=ref('')
  let toastTimer:ReturnType<typeof setTimeout>,generation=0
  const department=computed(()=>departments.value.find(d=>d.id===departmentId.value)), application=computed(()=>applications.value.find(a=>a.id===applicationId.value))
  const query=computed(()=>`?departmentId=${departmentId.value}&applicationId=${applicationId.value}`)
  const domainUsers=computed(()=>users.value.filter(u=>u.departmentIds.includes(departmentId.value)))
  function notify(message:string,error=false) {toast.value=message;toastError.value=error;clearTimeout(toastTimer);toastTimer=setTimeout(()=>toast.value='',4500)}
  async function load() {
    busy.value=true
    try {
      const [d,a,u,h]=await Promise.all([api<Department[]>('/admin/departments'),api<Application[]>('/admin/applications'),api<User[]>('/admin/users'),api<{status:string}>('/health')])
      departments.value=d;applications.value=a;users.value=u;health.value=h.status==='UP'
      overview.value={...overview.value,applications:a.length,departments:d.length,users:u.length}
      if(!d.some(x=>x.id===departmentId.value))departmentId.value=d[0]?.id||0
      if(!a.some(x=>x.id===applicationId.value))applicationId.value=a[0]?.id||0
      await loadScope()
    } catch(e) {health.value=false;notify((e as Error).message,true);throw e} finally {busy.value=false}
  }
  async function loadScope() {
    const current=++generation
    roles.value=[];permissions.value=[];overview.value={...overview.value,roles:0,permissions:0,members:0}
    if(!departmentId.value||!applicationId.value) {audits.value=await api<Audit[]>('/admin/audit');return}
    busy.value=true
    try {
      const q=query.value
      const [r,p,o,e]=await Promise.all([api<Role[]>(`/admin/roles${q}`),api<Permission[]>(`/admin/permissions${q}`),api<Overview>(`/admin/overview${q}`),api<Audit[]>('/admin/audit')])
      if(current!==generation)return
      roles.value=r;permissions.value=p;overview.value=o;audits.value=e
      localStorage.setItem('melonhub-department',String(departmentId.value));localStorage.setItem('melonhub-application',String(applicationId.value))
      lastUpdated.value=new Date().toLocaleTimeString('zh-CN',{hour12:false})
    } catch(e) {if(current===generation)notify((e as Error).message,true)} finally {if(current===generation)busy.value=false}
  }
  function clear() {generation++;departments.value=[];applications.value=[];users.value=[];roles.value=[];permissions.value=[];audits.value=[]}
  return {departments,applications,users,roles,permissions,audits,overview,departmentId,applicationId,department,application,query,domainUsers,busy,health,toast,toastError,lastUpdated,load,loadScope,notify,clear}
})

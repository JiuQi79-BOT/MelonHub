export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message) }
}
export async function api<T = unknown>(path: string, options: RequestInit = {}): Promise<T> {
  const token = sessionStorage.getItem('melonhub-token')
  const response = await fetch(`/api${path}`, {...options,headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`} : {}),...options.headers}})
  const body = await response.json().catch(()=>({message:'服务响应异常'}))
  if (!response.ok) {
    if (response.status===401 && body.code==='UNAUTHORIZED' && !path.includes('/auth/login')) {
      sessionStorage.removeItem('melonhub-token'); window.dispatchEvent(new Event('session-expired'))
    }
    throw new ApiError(response.status,body.code,body.message||'请求失败')
  }
  return body as T
}
export function json(method:string,body:unknown):RequestInit {return {method,body:JSON.stringify(body)}}

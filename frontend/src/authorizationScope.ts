import { inject, type InjectionKey } from 'vue'
import { useHub } from './store'

export type AuthorizationScope = Pick<ReturnType<typeof useHub>,
  'applicationId' | 'application' | 'hasDomain' | 'domainKey' | 'domainQuery' | 'canDomain'>

export const authorizationScopeKey: InjectionKey<AuthorizationScope> = Symbol('authorization-scope')

/** Drawer contents use the selected object without changing the surrounding page's domain. */
export function useAuthorizationScope(): AuthorizationScope {
  return inject(authorizationScopeKey, useHub())
}

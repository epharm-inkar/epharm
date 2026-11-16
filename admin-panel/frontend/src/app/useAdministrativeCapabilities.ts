import { useQuery } from '@tanstack/react-query'
import { accessApi } from '@/features/settings/access-api'
import { useUiStore } from './store'

/** Fresh server capability, never derived from a role title or persisted offline. */
export function useAdministrativeCapabilities() {
  const user = useUiStore((state) => state.authedUser)
  const query = useQuery({
    queryKey: ['admin-access', 'me', user?.id],
    queryFn: ({ signal }) => accessApi.me(signal),
    enabled: !!user,
    staleTime: 0,
    placeholderData: undefined,
    retry: false,
  })
  const fresh = query.isSuccess && query.isFetchedAfterMount && !query.isError
  return {
    ...query,
    // Mapping endpoints are NOT_READY for MANAGED accounts, even an HQ owner.
    // Never infer LEGACY from absent/stale capability data or weaken the backend gate.
    canManageStandardNMappings:
      fresh && query.data?.managed === false &&
      (user?.role === 'HQ_HEAD' || user?.role === 'SYSTEM_ADMIN'),
    canViewMerchInventory:
      query.isSuccess &&
      query.isFetchedAfterMount &&
      !query.isError &&
      query.data?.canViewMerchInventory === true,
    canViewMerchPlacements:
      query.isSuccess &&
      query.isFetchedAfterMount &&
      !query.isError &&
      query.data?.canViewMerchPlacements === true,
    canViewMerchTasks:
      query.isSuccess &&
      query.isFetchedAfterMount &&
      !query.isError &&
      query.data?.canViewMerchTasks === true,
    canCreateMerchTasks: fresh && query.data?.canCreateMerchTasks === true,
    canViewMerchStaffAccess: fresh && query.data?.canViewMerchStaffAccess === true,
    canViewMerchTariffs: fresh && query.data?.canViewMerchTariffs === true,
    canExecuteMerchTasks: fresh && query.data?.canExecuteMerchTasks === true,
    canReviewMerchTasks: fresh && query.data?.canReviewMerchTasks === true,
    canCommentMerchTasks: fresh && query.data?.canCommentMerchTasks === true,
    canCancelMerchTasks: fresh && query.data?.canCancelMerchTasks === true,
    canViewLedger: fresh && query.data?.canViewLedger === true,
    canExportLedger: fresh && query.data?.canExportLedger === true,
    canExportPayouts: fresh && query.data?.canExportPayouts === true,
    canViewRecommendationRewards: fresh && query.data?.canViewRecommendationRewards === true,
    canReviewRecommendationRewards: fresh && query.data?.canReviewRecommendationRewards === true,
    canExportRecommendationRewards: fresh && query.data?.canExportRecommendationRewards === true,
    canExportPharmacists: fresh && query.data?.canExportPharmacists === true,
    canExportLearnerCredentials: fresh && query.data?.canExportLearnerCredentials === true,
    canExportCampaignReports: fresh && query.data?.canExportCampaignReports === true,
    canExportMerchInventory: fresh && query.data?.canExportMerchInventory === true,
    canExportMerchPlacements: fresh && query.data?.canExportMerchPlacements === true,
    canExportMerchTasks: fresh && query.data?.canExportMerchTasks === true,
    canExportTrainingCourses: fresh && query.data?.canExportTrainingCourses === true,
    canExportTrainingPrograms: fresh && query.data?.canExportTrainingPrograms === true,
    canBindMerchProducer: fresh && query.data?.canBindMerchProducer === true,
    canManageCampaignProducer: fresh && query.data?.canManageCampaignProducer === true,
    canExportTrainingAssignments: fresh && query.data?.canExportTrainingAssignments === true,
    canExportTrainingResults: fresh && query.data?.canExportTrainingResults === true,
  }
}

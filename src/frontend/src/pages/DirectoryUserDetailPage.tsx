/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import DataState from "../components/common/DataState";
import PageHeader from "../components/layout/PageHeader";
import { toast } from "sonner";
import { UserDetailCard } from "../components/users/UserProfileSections";
import { UserRoleBadge } from "../components/users/UserComponents";
import useJson from "../hooks/useJson";
import useSubmissionGuard from "../hooks/useSubmissionGuard";
import { postForm } from "../utils/api";
import {
  resolveClientPath,
  resolvePostRedirectPath,
  SmartLink,
} from "../utils/routing";
import type { SessionPageProps } from "../types/app";
import type { DirectoryUserDetail } from "../types/domain";
import { Button } from "../components/ui/button";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "../components/ui/alert-dialog";

interface DirectoryUserDetailPageProps extends SessionPageProps {
  apiBase: string;
  backFallback: string;
}

function resolveActiveUrl(
  apiBase: string,
  id: string,
  role?: string,
): string | null {
  if (apiBase.startsWith("/api/admin/users")) {
    return `/api/admin/users/${id}/active`;
  }
  if (apiBase.startsWith("/api/support")) {
    return `/api/support/users/${id}/active`;
  }
  if (apiBase.startsWith("/api/tam")) {
    return `/api/tam/users/${id}/active`;
  }
  if (apiBase.startsWith("/api/superuser")) {
    return `/api/superuser/users/${id}/active`;
  }
  // TAM's user list (TamUserApiResource list) links to the shared
  // /user/user-profiles/:id route, not a TAM-specific detail route.
  // Map it to the TAM toggle endpoint, but only for TAM viewers —
  // regular "user" role viewers of the same apiBase must not get a toggle.
  if (apiBase.startsWith("/api/user/user-profiles") && role === "tam") {
    return `/api/tam/users/${id}/active`;
  }
  return null;
}

export default function DirectoryUserDetailPage({
  apiBase,
  backFallback,
  sessionState,
}: DirectoryUserDetailPageProps) {
  const navigate = useNavigate();
  const { id } = useParams();
  const [reloadKey, setReloadKey] = useState(0);

  const detailState = useJson<DirectoryUserDetail>(
    id ? `${apiBase}/${id}${reloadKey ? `?t=${reloadKey}` : ""}` : null,
  );
  const detail = detailState.data;
  const submissionGuard = useSubmissionGuard();

  const activeUrl = id
    ? resolveActiveUrl(apiBase, id, sessionState.data?.role)
    : null;
  const isAdminView = apiBase.startsWith("/api/admin/users");
  const sessionUsername = sessionState.data?.username;
  const isSelf =
    Boolean(sessionUsername) &&
    Boolean(detail?.username) &&
    sessionUsername!.toLowerCase() === detail!.username!.toLowerCase();
  const isToggleableType =
    isAdminView ||
    (detail?.type != null &&
      ["user", "external"].includes(detail.type.toLowerCase()));
  const showActiveToggle =
    activeUrl !== null &&
    detail?.active !== undefined &&
    !isSelf &&
    isToggleableType;

  const toggleActive = async () => {
    if (!activeUrl || detail?.active === undefined) {
      return;
    }
    if (!submissionGuard.tryEnter()) {
      return;
    }
    try {
      await postForm(activeUrl, [["active", !detail.active]]);
      toast.success(detail.active ? "User deactivated." : "User activated.");
      setReloadKey((key) => key + 1);
    } catch (error: unknown) {
      toast.error(
        error instanceof Error ? error.message : "Unable to update status.",
      );
    } finally {
      submissionGuard.exit();
    }
  };

  const deleteUser = async () => {
    if (!detail?.deletePath || !submissionGuard.tryEnter()) {
      return;
    }
    try {
      const response = await postForm(detail.deletePath, []);
      toast.success("User deleted.");
      navigate(
        await resolvePostRedirectPath(
          response,
          resolveClientPath(detail.backPath, backFallback),
        ),
      );
    } catch (error: unknown) {
      toast.error(
        error instanceof Error ? error.message : "Unable to delete user.",
      );
    } finally {
      submissionGuard.exit();
    }
  };

  return (
    <section className="w-full mt-4">
      <PageHeader
        title={
          <span className="flex items-center gap-2">
            {detail?.displayName ||
              detail?.fullName ||
              detail?.username ||
              "User"}
            {detail?.type && (
              <UserRoleBadge
                type={detail.type}
                className="text-[var(--color-section-header)]"
              />
            )}
          </span>
        }
      />

      <DataState state={detailState} emptyMessage="User not found.">
        {detail && (
          <UserDetailCard
            user={{
              username: detail.username,
              fullName: detail.fullName,
              email: detail.email,
              social: detail.social,
              phoneNumber: detail.phoneNumber,
              phoneExtension: detail.phoneExtension,
              type: detail.type,
              typeLabel: detail.typeLabel,
              countryName: detail.countryName,
              timezoneName: detail.timezoneName,
              companyName: detail.companyName,
              logoBase64: detail.logoBase64,
            }}
            companyHref={detail.companyPath}
            actions={
              <>
                {detail.deletePath && (
                  <AlertDialog>
                    <AlertDialogTrigger asChild>
                      <Button
                        type="button"
                        variant="destructive"
                        className="mr-auto"
                      >
                        Delete
                      </Button>
                    </AlertDialogTrigger>
                    <AlertDialogContent>
                      <AlertDialogHeader>
                        <AlertDialogTitle>Are you sure?</AlertDialogTitle>
                        <AlertDialogDescription>
                          This action cannot be undone. This will permanently
                          delete this user.
                        </AlertDialogDescription>
                      </AlertDialogHeader>
                      <AlertDialogFooter>
                        <AlertDialogCancel>Cancel</AlertDialogCancel>
                        <AlertDialogAction
                          onClick={deleteUser}
                          className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                        >
                          Delete
                        </AlertDialogAction>
                      </AlertDialogFooter>
                    </AlertDialogContent>
                  </AlertDialog>
                )}
                {showActiveToggle && (
                  <Button
                    type="button"
                    variant={detail.active ? "outline" : "default"}
                    className={detail.deletePath ? undefined : "ml-auto"}
                    onClick={toggleActive}
                  >
                    {detail.active ? "Deactivate" : "Activate"}
                  </Button>
                )}
                {detail.editPath && (
                  <Button asChild>
                    <SmartLink href={detail.editPath}>Edit</SmartLink>
                  </Button>
                )}
              </>
            }
          />
        )}
      </DataState>
    </section>
  );
}

# Email setup — Microsoft 365 (Microsoft Graph)

Audience: Meridian IT. Time needed: about 20 minutes, plus a Global Administrator
for one approval step.

The attendance server sends exactly two kinds of message:

| Message | Subject | Sent when |
| --- | --- | --- |
| Invitation | `Set up your Meridian Attendance account` | an administrator adds an employee, or resends the invitation |
| Password reset | `Reset your Meridian Attendance password` | someone uses *Forgot password* |

Nothing else is ever sent — no marketing, no reports, no reminders.

Until email works, nobody can be invited: administrators have to read a temporary
password out to each employee, and *Forgot password* silently does nothing.

---

## 1. Why Microsoft Graph and not SMTP

Microsoft is retiring basic authentication for SMTP client submission in Exchange
Online. Per Microsoft's Exchange team, basic auth for SMTP AUTH keeps working
through December 2026; at the end of December 2026 it is **disabled by default for
existing tenants** (an admin can still re-enable it temporarily), and tenants
created after December 2026 have it disabled from the start with OAuth as the
supported method. The final retirement date is to be announced in the second half
of 2027. See Microsoft's posts
["Exchange Online to retire Basic auth for Client Submission (SMTP AUTH)"](https://techcommunity.microsoft.com/blog/exchange/exchange-online-to-retire-basic-auth-for-client-submission-smtp/4114750)
and ["Updated Exchange Online SMTP AUTH Basic Authentication Deprecation Timeline"](https://techcommunity.microsoft.com/blog/Exchange/updated-exchange-online-smtp-auth-basic-authentication-deprecation-timeline/4489835).

A username-and-password SMTP relay is therefore a dead end for Meridian. The
server instead authenticates to Microsoft Graph as an *application* (client
credentials) and calls `sendMail` on one named mailbox. There is no user password
anywhere, the secret can be rotated without touching any employee, and the
permission can be restricted to that single mailbox (step 5).

The SMTP path is still in the code and still supported — use it only if Meridian
moves to a provider that is not Microsoft 365 (`EMAIL_PROVIDER=smtp`).

---

## 2. Decide on the sender mailbox

Pick (or create) one mailbox the invitations come from, for example
`attendance@meridiangroup.co.za`. It must be a real Exchange Online mailbox in the
tenant — a shared mailbox is fine and needs no licence. Employees will see it as
the sender; replies to it are not read by the system, so point it at a monitored
shared mailbox if you want replies to go anywhere.

## 3. Register the application in Microsoft Entra ID

In the Microsoft Entra admin centre (or the Azure portal), under **App
registrations**:

1. **New registration**. Name it something recognisable, e.g.
   `Meridian Attendance — email sender`.
2. Supported account types: **Accounts in this organizational directory only**
   (single tenant).
3. Leave the redirect URI empty — this application never signs a user in.
4. **Register**.

From the registration's overview page, copy:

* **Application (client) ID** → `MS_GRAPH_CLIENT_ID`
* **Directory (tenant) ID** → `MS_GRAPH_TENANT_ID`

## 4. Grant the Mail.Send application permission

Still in the registration, open **API permissions**:

1. **Add a permission** → **Microsoft Graph** → **Application permissions**
   (not *Delegated* — there is no signed-in user).
2. Search for and tick **`Mail.Send`**.
3. **Add permissions**.
4. **Grant admin consent for \<tenant>**. This button needs a Global Administrator
   (or Privileged Role Administrator). The permission is not active until the
   status column shows it as granted.

Grant nothing else. `Mail.Send` alone is enough, and on its own it allows sending
as *any* mailbox in the tenant — which is why step 5 matters.

## 5. Restrict the application to the one mailbox

An Exchange **application access policy** limits the registration to a group of
mailboxes. Do this before putting the secret on the server.

Create a mail-enabled security group (for example
`sg-attendance-mail-senders@meridiangroup.co.za`) whose only member is the sender
mailbox from step 2, then, with the ExchangeOnlineManagement PowerShell module:

```powershell
Install-Module ExchangeOnlineManagement -Scope CurrentUser   # once, if not present
Connect-ExchangeOnline -UserPrincipalName admin@meridiangroup.co.za

New-ApplicationAccessPolicy `
    -AppId "<Application (client) ID from step 3>" `
    -PolicyScopeGroupId "sg-attendance-mail-senders@meridiangroup.co.za" `
    -AccessRight RestrictAccess `
    -Description "Meridian Attendance may send only as attendance@meridiangroup.co.za"
```

Verify it (policy changes can take up to about an hour to apply):

```powershell
Test-ApplicationAccessPolicy -Identity attendance@meridiangroup.co.za -AppId "<Application (client) ID>"
# AccessCheckResult : Granted

Test-ApplicationAccessPolicy -Identity someone.else@meridiangroup.co.za -AppId "<Application (client) ID>"
# AccessCheckResult : Denied
```

To see or remove policies later: `Get-ApplicationAccessPolicy`,
`Remove-ApplicationAccessPolicy -Identity <id>`.

## 6. Create the client secret

In the registration under **Certificates & secrets** → **Client secrets** →
**New client secret**. Give it a description and the shortest expiry that suits
your rotation schedule (24 months maximum).

Copy the secret **Value** immediately — it is shown once and never again. The
*Secret ID* is not the secret.

Put the value straight into the server's environment. Never commit it, never mail
it, never paste it into a ticket.

## 7. Configure the server

Set these environment variables (see `deploy/env/production.env.example`):

```ini
EMAIL_PROVIDER=auto
MS_GRAPH_TENANT_ID=<Directory (tenant) ID>
MS_GRAPH_CLIENT_ID=<Application (client) ID>
MS_GRAPH_CLIENT_SECRET=<the secret Value>
EMAIL_SENDER_ADDRESS=attendance@meridiangroup.co.za
EMAIL_SENDER_NAME=Meridian Attendance
```

`EMAIL_PROVIDER=auto` uses Graph when all four Graph values are present, otherwise
SMTP when `SMTP_HOST` is set, otherwise nothing. Force the choice with `graph`,
`smtp` or `none`. Restart the server after changing any of them.

Outbound HTTPS to `login.microsoftonline.com` and `graph.microsoft.com` must be
allowed from the server.

## 8. Test it

Sign in to the dashboard as an administrator and check the server's view:

```
GET /api/v1/admin/email/status
→ {"provider":"graph","configured":true,"sender":"attendance@meridiangroup.co.za"}

POST /api/v1/admin/email/test
→ {"sent":true,"detail":"Test message sent to you@meridiangroup.co.za via graph."}
```

The test message goes to the signed-in administrator's own address. The endpoint
is rate-limited to a few calls a minute.

Then invite one real employee and watch it arrive before inviting everybody.

## 9. When something goes wrong

The server never logs the secret or the access token. Check the server log for the
failing call; the HTTP status and Microsoft's error code are enough to identify
the cause.

| Symptom | Cause | Fix |
| --- | --- | --- |
| `status` says `provider: none` | one of the four Graph settings is missing | compare against step 7, restart |
| token request fails `401 invalid_client` | wrong secret, or it expired | create a new secret (step 6) |
| token request fails `400 unauthorized_client` | wrong tenant or client ID | re-copy both from the registration |
| sendMail `403 ErrorAccessDenied` | admin consent not granted, or the access policy excludes the mailbox | finish step 4; run `Test-ApplicationAccessPolicy` from step 5 |
| sendMail `404` | `EMAIL_SENDER_ADDRESS` is not a mailbox in this tenant | check the address |
| sendMail `429` | Graph throttling | the server reports it and does not retry; try again shortly |
| Employees never receive the mail | tenant anti-spam or an external filter | check Exchange message trace for the recipient |

If Graph cannot be used at all, set `EMAIL_PROVIDER=smtp` with a working relay as a
stopgap, and tell the administrators that invitations fall back to temporary
passwords whenever email is off.

import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { InviteApi, InviteDetails } from '../../core/api/endpoints';
import { IconComponent } from '../../ui/icon.component';

@Component({
  selector: 'fe-invite-accept',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './invite-accept.component.html',
  styleUrl: './invite-accept.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InviteAcceptComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly inviteApi = inject(InviteApi);

  protected readonly token = signal<string>('');
  protected readonly loading = signal<boolean>(true);
  protected readonly validating = signal<boolean>(true);
  protected readonly inviteDetails = signal<InviteDetails | null>(null);
  protected readonly error = signal<string | null>(null);

  protected password = '';
  protected confirmPassword = '';
  protected submitting = signal<boolean>(false);
  protected success = signal<boolean>(false);

  ngOnInit(): void {
    const queryToken = this.route.snapshot.queryParamMap.get('token') || this.route.snapshot.paramMap.get('token');
    if (!queryToken) {
      this.validating.set(false);
      this.error.set('No invite token was provided in the link. Please check your invitation email.');
      return;
    }

    this.token.set(queryToken);
    void this.checkToken(queryToken);
  }

  async checkToken(token: string): Promise<void> {
    this.validating.set(true);
    this.error.set(null);
    try {
      const details = await this.inviteApi.validateInvite(token);
      this.inviteDetails.set(details);
    } catch (err: any) {
      if (err?.status === 410 || err?.code === 'INVITE_ALREADY_USED') {
        this.error.set('This single-use invite has already been claimed. Please sign in with your credentials.');
      } else if (err?.status === 404) {
        this.error.set('This invite link is invalid or has expired. Please contact your platform operator for a new invite.');
      } else {
        this.error.set(err?.message || 'Unable to validate invite link. Please check your connection.');
      }
    } finally {
      this.validating.set(false);
    }
  }

  async submitAccept(): Promise<void> {
    this.error.set(null);

    if (!this.password || this.password.length < 8) {
      this.error.set('Password must be at least 8 characters long.');
      return;
    }

    if (this.password !== this.confirmPassword) {
      this.error.set('Passwords do not match. Please re-enter your password.');
      return;
    }

    this.submitting.set(true);
    try {
      await this.inviteApi.acceptInvite(this.token(), this.password);
      this.success.set(true);
    } catch (err: any) {
      if (err?.status === 410 || err?.code === 'INVITE_ALREADY_USED') {
        this.error.set('This invite link was already used. Please proceed to login.');
      } else {
        this.error.set(err?.message || 'Failed to accept invitation. Please try again.');
      }
    } finally {
      this.submitting.set(false);
    }
  }

  goToLogin(): void {
    void this.router.navigate(['/login'], {
      queryParams: { email: this.inviteDetails()?.email },
    });
  }
}

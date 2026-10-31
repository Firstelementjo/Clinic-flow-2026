import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * The root component.
 *
 * A router outlet and nothing else. All state lives in services and the shell, so
 * that a hard refresh on any route rehydrates from the same single source — the
 * session — rather than from a component that happened to survive a navigation.
 */
@Component({
  selector: 'fe-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet],
  template: `<router-outlet />`,
})
export class AppComponent {}

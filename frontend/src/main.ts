import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { appConfig } from './app/app.config';

bootstrapApplication(AppComponent, appConfig).catch((error: unknown) => {
  // A bootstrap failure is the one error with no UI to show it on, so it goes to
  // the console with the reason intact rather than being swallowed.
  console.error('FirstElement failed to start', error);
});

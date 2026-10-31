/**
 * MotionService — GSAP + Lenis + React Bits interactive motion engine.
 *
 * Provides:
 * 1. Lenis smooth inertial scrolling on the shell work area (`.tl-content`),
 *    synchronized with `gsap.ticker`.
 * 2. GSAP staggered entrance timelines on route transitions and view updates:
 *    - React Bits BlurText reveal on page titles, greetings, and breadcrumbs
 *    - Staggered spring-like card & bento panel entrances
 *    - React Bits CountUp animated numeric tickers on stat cards
 *    - SVG revenue chart line draw-in and data point pop-in
 *    - Table row cascade animations
 * 3. React Bits SpotlightCard & Magnetic micro-interactions via delegated
 *    pointer tracking (`--rb-mouse-x`, `--rb-mouse-y`).
 *
 * Automatically disables in `jsdom` unit tests and when the user's OS/browser
 * requests `prefers-reduced-motion: reduce`.
 */
import { Injectable, NgZone, OnDestroy, inject } from '@angular/core';
import gsap from 'gsap';
import Lenis from 'lenis';

@Injectable({ providedIn: 'root' })
export class MotionService implements OnDestroy {
  private readonly zone = inject(NgZone);

  private lenis: Lenis | null = null;
  private tickerCallback: ((time: number) => void) | null = null;
  private cleanupPointerListeners: (() => void) | null = null;
  private activeTimeline: gsap.core.Timeline | null = null;
  private animRafId: number | null = null;

  /**
   * Returns true only in a real browser viewport where motion is permitted.
   * Skips jsdom unit tests and respects `prefers-reduced-motion: reduce`.
   */
  canAnimate(): boolean {
    if (typeof window === 'undefined' || typeof document === 'undefined') {
      return false;
    }
    const ua = typeof navigator !== 'undefined' ? navigator.userAgent ?? '' : '';
    if (ua.toLowerCase().includes('jsdom')) {
      return false;
    }
    if (
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches
    ) {
      return false;
    }
    return true;
  }

  /**
   * Attaches Lenis smooth scrolling + React Bits SpotlightCard pointer tracking
   * to the shell's scrollable `<main class="tl-content">` container.
   */
  attachScrollContainer(wrapper: HTMLElement): void {
    if (!this.canAnimate()) return;

    this.destroyScrollContainer();

    this.zone.runOutsideAngular(() => {
      try {
        const lenis = new Lenis({
          wrapper,
          content: (wrapper.firstElementChild as HTMLElement) ?? wrapper,
          lerp: 0.11,
          duration: 1.05,
          smoothWheel: true,
          wheelMultiplier: 0.95,
          touchMultiplier: 1.5,
          autoResize: true,
        });

        const tick = (time: number): void => {
          lenis.raf(time * 1000);
        };

        gsap.ticker.add(tick);
        gsap.ticker.lagSmoothing(0);

        this.lenis = lenis;
        this.tickerCallback = tick;
      } catch {
        // Fallback gracefully to native scroll if Lenis cannot bind.
      }

      this.cleanupPointerListeners = this.bindReactBitsInteractions(wrapper);
    });
  }

  /**
   * Binds React Bits SpotlightCard radial cursor tracking (`--rb-mouse-x`, `--rb-mouse-y`)
   * and subtle magnetic icon lift onto interactive surfaces inside `root`.
   */
  bindReactBitsInteractions(root: HTMLElement): () => void {
    if (!this.canAnimate()) return () => undefined;

    const spotlightSelector =
      '.tl-stat-card, .tl-card, .tl-chart-card, .tl-side-card, .fe-card, .login__card, .tl-upcoming-item';

    const onPointerMove = (event: PointerEvent): void => {
      const target = event.target as HTMLElement | null;
      if (!target) return;

      const card = target.closest<HTMLElement>(spotlightSelector);
      if (card) {
        const rect = card.getBoundingClientRect();
        const x = event.clientX - rect.left;
        const y = event.clientY - rect.top;
        card.style.setProperty('--rb-mouse-x', `${x.toFixed(1)}px`);
        card.style.setProperty('--rb-mouse-y', `${y.toFixed(1)}px`);
      }

      // React Bits TiltedCard 3D perspective tilt + Magnetic icon pull on `.tl-stat-card`
      const statCard = target.closest<HTMLElement>('.tl-stat-card, .clinic-hub-card');
      if (statCard) {
        const rect = statCard.getBoundingClientRect();
        const relX = (event.clientX - (rect.left + rect.width / 2)) / (rect.width / 2);
        const relY = (event.clientY - (rect.top + rect.height / 2)) / (rect.height / 2);

        gsap.to(statCard, {
          rotateY: relX * 4.5,
          rotateX: -relY * 4.5,
          y: -3,
          transformPerspective: 900,
          duration: 0.3,
          ease: 'power2.out',
          overwrite: 'auto',
        });

        const icon = statCard.querySelector<HTMLElement>('.tl-stat-icon');
        if (icon) {
          gsap.to(icon, {
            x: relX * 6,
            y: relY * 6,
            scale: 1.08,
            duration: 0.35,
            ease: 'power2.out',
            overwrite: 'auto',
          });
        }
      }
    };

    const onPointerOut = (event: PointerEvent): void => {
      const target = event.target as HTMLElement | null;
      if (!target) return;
      const statCard = target.closest<HTMLElement>('.tl-stat-card, .clinic-hub-card');
      const related = event.relatedTarget as Node | null;
      if (statCard && (!related || !statCard.contains(related))) {
        gsap.to(statCard, {
          rotateY: 0,
          rotateX: 0,
          y: 0,
          duration: 0.55,
          ease: 'elastic.out(1, 0.6)',
          clearProps: 'transform',
          overwrite: 'auto',
        });
        const icon = statCard.querySelector<HTMLElement>('.tl-stat-icon');
        if (icon) {
          gsap.to(icon, {
            x: 0,
            y: 0,
            scale: 1,
            duration: 0.45,
            ease: 'elastic.out(1, 0.5)',
            overwrite: 'auto',
          });
        }
      }
    };

    root.addEventListener('pointermove', onPointerMove, { passive: true });
    root.addEventListener('pointerout', onPointerOut, { passive: true });

    return () => {
      root.removeEventListener('pointermove', onPointerMove);
      root.removeEventListener('pointerout', onPointerOut);
    };
  }

  /**
   * Smoothly scrolls the active Lenis container back to the top on route change
   * and triggers a GSAP + React Bits staggered page entrance animation.
   */
  animateRouteTransition(container: HTMLElement): void {
    if (!this.canAnimate()) return;

    this.zone.runOutsideAngular(() => {
      if (this.lenis) {
        this.lenis.resize();
        this.lenis.scrollTo(0, { immediate: true });
      } else {
        container.scrollTop = 0;
      }

      if (this.animRafId !== null) {
        cancelAnimationFrame(this.animRafId);
      }

      this.animRafId = requestAnimationFrame(() => {
        this.animRafId = null;
        this.animatePageElements(container);
      });
    });
  }

  /**
   * Runs the GSAP staggered entrance timeline for headings, cards, CountUp numbers,
   * SVG charts, and table rows inside `container`.
   */
  animatePageElements(container: HTMLElement): void {
    if (!this.canAnimate()) return;

    this.zone.runOutsideAngular(() => {
      this.activeTimeline?.kill();

      const tl = gsap.timeline({
        defaults: { ease: 'power3.out' },
      });
      this.activeTimeline = tl;

      // 1. React Bits BlurText reveal on breadcrumbs, greetings, and page headers
      const headings = Array.from(
        container.querySelectorAll<HTMLElement>(
          '.tl-breadcrumbs, .tl-greeting h1, .tl-greeting p, .fe-page-header, .tl-toolbar',
        ),
      );
      if (headings.length > 0) {
        tl.fromTo(
          headings,
          { opacity: 0, y: 10, filter: 'blur(5px)' },
          {
            opacity: 1,
            y: 0,
            filter: 'blur(0px)',
            duration: 0.42,
            stagger: 0.04,
            clearProps: 'transform,opacity,filter',
          },
          0,
        );
      }

      // 2. Staggered Bento / Stat / Table Card reveal
      const cards = Array.from(
        container.querySelectorAll<HTMLElement>(
          '.tl-stat-card, .tl-chart-card, .tl-side-card, .tl-card, .fe-card, .tl-table-wrap, .tl-calendar-section',
        ),
      );
      if (cards.length > 0) {
        tl.fromTo(
          cards,
          { opacity: 0, y: 18, scale: 0.985 },
          {
            opacity: 1,
            y: 0,
            scale: 1,
            duration: 0.48,
            stagger: 0.045,
            clearProps: 'transform,opacity',
          },
          0.05,
        );
      }

      // 3. Table rows subtle cascade
      const rows = Array.from(
        container.querySelectorAll<HTMLElement>('.tl-table tbody tr, .tl-upcoming-item'),
      ).slice(0, 16);
      if (rows.length > 0) {
        tl.fromTo(
          rows,
          { opacity: 0, y: 8 },
          {
            opacity: 1,
            y: 0,
            duration: 0.32,
            stagger: 0.02,
            clearProps: 'transform,opacity',
          },
          0.12,
        );
      }

      // 4. SVG chart line draw-in + dots pop
      const chartLine = container.querySelector<SVGPathElement>('.tl-chart-line');
      if (chartLine && typeof chartLine.getTotalLength === 'function') {
        try {
          const length = chartLine.getTotalLength();
          if (length > 0) {
            tl.fromTo(
              chartLine,
              { strokeDasharray: length, strokeDashoffset: length },
              {
                strokeDashoffset: 0,
                duration: 0.95,
                ease: 'power2.inOut',
                clearProps: 'strokeDasharray,strokeDashoffset',
              },
              0.15,
            );
          }
        } catch {
          // Ignore if SVG path is not yet measurable
        }
      }

      const chartDots = Array.from(container.querySelectorAll<SVGCircleElement>('.tl-chart-dot'));
      if (chartDots.length > 0) {
        tl.fromTo(
          chartDots,
          { scale: 0, transformOrigin: 'center center' },
          {
            scale: 1,
            duration: 0.35,
            stagger: 0.035,
            ease: 'back.out(2)',
            clearProps: 'transform',
          },
          0.28,
        );
      }

      // 5. React Bits CountUp ticker on stat card values
      this.animateCountUpValues(container);
    });
  }

  /**
   * React Bits CountUp effect: smoothly counts numeric displays from 0 to their rendered value
   * while preserving currency symbols, commas, or decimals.
   */
  animateCountUpValues(container: HTMLElement): void {
    if (!this.canAnimate()) return;

    const valueNodes = Array.from(
      container.querySelectorAll<HTMLElement>('.tl-stat-value, .tl-rev-big'),
    );

    for (const node of valueNodes) {
      const rawText = (node.textContent ?? '').trim();
      if (!rawText) continue;

      const match = /^([^0-9-]*)(-?[0-9][0-9,]*(?:\.[0-9]+)?)(.*)$/.exec(rawText);
      if (!match) continue;

      const [, prefix, numPart, suffix] = match;
      const cleanNum = numPart.replace(/,/g, '');
      const targetValue = Number.parseFloat(cleanNum);
      if (!Number.isFinite(targetValue) || targetValue === 0) continue;

      const hasCommas = numPart.includes(',');
      const decimalIndex = cleanNum.indexOf('.');
      const decimals = decimalIndex >= 0 ? cleanNum.length - decimalIndex - 1 : 0;

      const state = { val: 0 };
      gsap.to(state, {
        val: targetValue,
        duration: 0.85,
        ease: 'power2.out',
        onUpdate: () => {
          const formatted =
            decimals > 0
              ? state.val.toFixed(decimals)
              : Math.round(state.val).toString();
          const withCommas = hasCommas
            ? formatted.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
            : formatted;
          node.textContent = `${prefix}${withCommas}${suffix}`;
        },
        onComplete: () => {
          node.textContent = rawText;
        },
      });
    }
  }

  /**
   * Animates the Login screen entrance (Aurora hero panel + Spotlight login card).
   */
  animateLoginEntrance(host: HTMLElement): () => void {
    if (!this.canAnimate()) return () => undefined;

    let cleanupPointer: () => void = () => undefined;
    this.zone.runOutsideAngular(() => {
      cleanupPointer = this.bindReactBitsInteractions(host);

      const tl = gsap.timeline({ defaults: { ease: 'power3.out' } });
      const brand = host.querySelector<HTMLElement>('.login__brand');
      const card = host.querySelector<HTMLElement>('.login__card');
      const panelCopy = host.querySelector<HTMLElement>('.login__panel-copy');
      const fields = Array.from(host.querySelectorAll<HTMLElement>('.login__field, .login__submit'));

      if (brand) {
        tl.fromTo(
          brand,
          { opacity: 0, y: -12 },
          { opacity: 1, y: 0, duration: 0.45, clearProps: 'transform,opacity' },
          0,
        );
      }
      if (card) {
        tl.fromTo(
          card,
          { opacity: 0, y: 22, scale: 0.98 },
          { opacity: 1, y: 0, scale: 1, duration: 0.55, clearProps: 'transform,opacity' },
          0.06,
        );
      }
      if (fields.length > 0) {
        tl.fromTo(
          fields,
          { opacity: 0, y: 12 },
          {
            opacity: 1,
            y: 0,
            duration: 0.4,
            stagger: 0.055,
            clearProps: 'transform,opacity',
          },
          0.16,
        );
      }
      if (panelCopy) {
        tl.fromTo(
          panelCopy,
          { opacity: 0, y: 20, filter: 'blur(6px)' },
          {
            opacity: 1,
            y: 0,
            filter: 'blur(0px)',
            duration: 0.65,
            clearProps: 'transform,opacity,filter',
          },
          0.12,
        );
      }
    });

    return cleanupPointer;
  }

  destroyScrollContainer(): void {
    if (this.animRafId !== null && typeof cancelAnimationFrame === 'function') {
      cancelAnimationFrame(this.animRafId);
      this.animRafId = null;
    }
    this.activeTimeline?.kill();
    this.activeTimeline = null;

    this.cleanupPointerListeners?.();
    this.cleanupPointerListeners = null;

    if (this.tickerCallback) {
      gsap.ticker.remove(this.tickerCallback);
      this.tickerCallback = null;
    }
    if (this.lenis) {
      this.lenis.destroy();
      this.lenis = null;
    }
  }

  ngOnDestroy(): void {
    this.destroyScrollContainer();
  }
}

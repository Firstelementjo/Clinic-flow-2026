/**
 * Does `ValidationPipe({ whitelist: true })` — the exact configuration at
 * `apps/api/src/main.ts:49` — actually strip an undeclared property from a
 * plain-object `@Body()` parameter?
 *
 * This decides reachability, so it is measured rather than assumed. Nest's
 * `whitelist` strips properties that carry no validation decorator, but only
 * when the handler parameter's runtime metatype is a *class*. `@Body() body: {
 * title: string }` and `@Body() body: any` both erase to `Object` at runtime
 * (an inline object type literal has no runtime class), for which
 * `ValidationPipe.toValidate()` returns false and the payload is passed through
 * untouched.
 *
 * The consequence is load-bearing for this sweep: if undeclared body properties
 * survive, then a TypeScript body type that omits `projectId` is **not** a
 * security boundary, and every service that reads a project id off the request
 * body is reachable by an attacker who simply adds the field.
 *
 * `PlainBodyController` mirrors `tasks.controller.ts` (`@Body() body: { title:
 * string }`). `AnyBodyController` mirrors `clinic.controller.ts`
 * (`@Body() body: any`).
 */
import 'reflect-metadata';
import { Body, Controller, INestApplication, Module, Post, ValidationPipe } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { IsString } from 'class-validator';
import request from 'supertest';
import { describe, expect, it } from 'vitest';

/** Mirrors `tasks.controller.ts`: a plain object type, no `projectId` declared. */
@Controller('plain')
class PlainBodyController {
  @Post()
  create(@Body() body: { title: string }) {
    return body;
  }
}

/**
 * Mirrors `clinic.controller.ts`: `@Body() body: any`.
 *
 * <p>Typed as `Record<string, unknown>` rather than `any`, and the narrower type
 * is not a compromise — it is the *same* type at runtime, and the runtime type is
 * the whole thing under test. TypeScript emits `Object` as the design-time
 * metatype for `any`, for an inline object literal, and for an index-signature
 * type, because none of them names a class. `ValidationPipe.toValidate()` skips
 * a metatype of `Object`, so all three reach the handler untouched — which is why
 * the sibling `PlainBodyController` above already passes.
 *
 * <p>So this is not "an `any` that happens to typecheck". `Record<string,
 * unknown>` says precisely what the value is at this boundary: an object of
 * unknown-valued keys, because the request body was never validated. That is the
 * honest description, and it is the one that makes the finding visible — a reader
 * can see that nothing constrained the keys.
 */
@Controller('any')
class AnyBodyController {
  @Post()
  create(@Body() body: Record<string, unknown>) {
    return body;
  }
}

@Module({ controllers: [PlainBodyController, AnyBodyController] })
class ProbeModule {}

/** A real class DTO, used only by the decision-function control below. */
class NoteDto {
  @IsString()
  title!: string;
}

const INJECTED = {
  title: 'legitimate',
  projectId: '11111111-1111-1111-1111-111111111111',
  organizationId: '22222222-2222-2222-2222-222222222222',
};

async function boot(): Promise<INestApplication> {
  const moduleRef = await Test.createTestingModule({ imports: [ProbeModule] }).compile();
  const app = moduleRef.createNestApplication();
  // Byte-for-byte the configuration from apps/api/src/main.ts:49.
  app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));
  await app.init();
  return app;
}

describe('ValidationPipe whitelist reachability (apps/api/src/main.ts:49)', () => {
  it('does NOT strip an undeclared property from a plain-object @Body()', async () => {
    const app = await boot();
    try {
      const response = await request(app.getHttpServer()).post('/plain').send(INJECTED).expect(201);

      // This is the finding: `projectId` is absent from the TypeScript type,
      // and it still reaches the handler.
      expect(response.body.projectId).toBe(INJECTED.projectId);
      expect(response.body.organizationId).toBe(INJECTED.organizationId);
    } finally {
      await app.close();
    }
  });

  it('does NOT strip anything from an `any` @Body()', async () => {
    const app = await boot();
    try {
      const response = await request(app.getHttpServer()).post('/any').send(INJECTED).expect(201);

      expect(response.body.projectId).toBe(INJECTED.projectId);
      expect(response.body.organizationId).toBe(INJECTED.organizationId);
    } finally {
      await app.close();
    }
  });
});

/**
 * The control. It cannot be expressed through decorators in this harness
 * because esbuild — which Vite uses — does not implement
 * `emitDecoratorMetadata`, so `design:paramtypes` is never emitted and a DTO
 * parameter cannot reach the pipe as a class at runtime. Decorating a DTO
 * controller here would therefore prove nothing: the pipe would see `Object`
 * either way, for the same reason as the failing cases.
 *
 * So the control exercises the pipe's decision function directly, passing the
 * metatype exactly as Nest does internally. That is the actual difference
 * between the two cases — `toValidate()` skips a metatype of `Object` — and
 * it is what makes the two results above a statement about *metatype* rather
 * than about a broken pipe.
 */
describe('ValidationPipe whitelist decision function', () => {
  const pipe = new ValidationPipe({ whitelist: true, transform: true });

  it('strips an undeclared property when the metatype is a class', async () => {
    const result = (await pipe.transform(INJECTED, {
      type: 'body',
      metatype: NoteDto,
    })) as Record<string, unknown>;

    expect(result.title).toBe('legitimate');
    expect(result.projectId).toBeUndefined();
    expect(result.organizationId).toBeUndefined();
  });

  it('strips nothing when the metatype is Object — what a plain object type and `any` both produce', async () => {
    const result = (await pipe.transform(INJECTED, {
      type: 'body',
      metatype: Object,
    })) as Record<string, unknown>;

    expect(result.title).toBe('legitimate');
    expect(result.projectId).toBe(INJECTED.projectId);
    expect(result.organizationId).toBe(INJECTED.organizationId);
  });
});

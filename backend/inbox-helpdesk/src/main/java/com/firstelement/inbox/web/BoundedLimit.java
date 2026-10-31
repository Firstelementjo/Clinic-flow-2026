package com.firstelement.inbox.web;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A bounded page size, declared ONCE so the four cursor-paginated routes cannot drift again.
 *
 * <h2>Why this annotation exists at all</h2>
 *
 * <p>ADR 0010's remaining contract defect was that {@code limit} was published as
 * {@code {"type":"string"}} on the inbox, conversations, customers and clinic-schedule routes while
 * six other paginated routes published {@code {"type":"integer","format":"int32"}}. The cause was
 * not a schema mistake but a <em>signature</em> one: those four routes declared
 * {@code @RequestParam(required = false) String limit} and handed-parse it through
 * {@link RequestParams#limit}, and springdoc faithfully describes what it is given.
 *
 * <p>So the fix is not to change four signatures. It is to make the correct declaration the only
 * one that is easy to write.
 *
 * <h2>Why the type is integer while the parameter is still read as a string</h2>
 *
 * <p>Because the two answer different questions, and conflating them is what produced the defect.
 *
 * <ul>
 *   <li><b>The contract</b> answers "what may a client send?" — a number. Every generated client
 *       built from this document will therefore send a number, which is what the server wants.</li>
 *   <li><b>The server's leniency</b> answers "what do we do with a client that ignores that?" — it
 *       clamps an over-large value to the maximum and falls back to the default for a blank or
 *       non-numeric one. {@link RequestParams#limit} documents why: the legacy zod schema rejected
 *       it, and a bounded clamp is the friendlier and equally safe behaviour.</li>
 * </ul>
 *
 * <p>Tolerance for malformed input is <em>not</em> part of a client's contract, so publishing it as
 * {@code string} told every generated client it might legitimately send a word. Declaring
 * {@code integer} while keeping the clamp says exactly what is true: send a number, and if you send
 * nonsense you will quietly get the default rather than an error.
 *
 * <p>The six operations routes already publish {@code integer}, so this makes the document
 * internally consistent without changing any of them, and without changing the behaviour of a single
 * route.
 *
 * @see RequestParams#limit(String, int, int)
 *
 * <h2>Why it does not also carry {@code @RequestParam}</h2>
 *
 * <p>Because it cannot. Spring's {@code @RequestParam} is declared
 * {@code @Target(ElementType.PARAMETER)} only, so applying it to an annotation <em>type</em> is a
 * compile error: <em>"annotation interface not applicable to this kind of declaration"</em>. That is
 * worth recording because composing it looks like the obvious thing to do and fails at build time.
 * Swagger's {@code @Parameter} does list {@code ANNOTATION_TYPE}, so this annotation carries the
 * schema half and each call site keeps its own {@code @RequestParam}.
 *
 * <p>Used as:
 *
 * <pre>{@code
 * public Page customers(@RequestParam(name = "limit", required = false) @BoundedLimit String limit) {
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Parameter(
    description = "Maximum rows to return. A value above the maximum is clamped to it rather than "
        + "rejected, and a blank or non-numeric value falls back to the default.",
    schema = @Schema(type = "integer", format = "int32", minimum = "1"))
public @interface BoundedLimit {
}

-- =====================================================================
-- pn04-extra-membership-spring.sql
--
-- PN-04: agent-a1 was granted a membership of project A2 in the Spring
--         database.
--
-- WHAT THIS PROVES. CHK-3 must turn red on the DECISION comparison.
--
-- This is the most important perturbation in the set, and it is deliberately
-- NOT a test of whether the harness notices a data difference. It asks a
-- different question: is the authorization decision actually DERIVED from
-- the membership rows, or is it a constant that both stacks happen to
-- agree on?
--
-- After this edit the Spring runtime must ALLOW `customers.projA2.agentA1`
-- while the legacy runtime still DENIES it. If the decision comparison were
-- comparing a constant to a constant -- or if both runs were somehow reading
-- the same membership state -- the edit would change nothing and the check
-- would stay green. A green CHK-3 that survives this edit is not evidence of
-- tenant isolation; it is evidence that CHK-3 is not looking.
--
-- Note the asymmetry this creates on purpose: the two databases now disagree
-- about who agent-a1 is. That disagreement is the signal.
-- =====================================================================
INSERT INTO project_memberships ("id", "organizationId", "projectId", "userId", "createdAt")
VALUES ('0d000001-0000-4000-8000-0000000000f1',
        '11111111-1111-4111-8111-111111111111',
        'a1a1a1a1-0000-4000-8000-000000000002',
        '0a000001-0000-4000-8000-000000000002',
        '2026-01-25 09:00:00');
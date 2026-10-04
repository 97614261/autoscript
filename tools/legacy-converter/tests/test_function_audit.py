import json
import unittest
from legacy_converter.core import Job
from legacy_converter.function_audit import argument_shape, audit_functions


class FunctionAuditTests(unittest.TestCase):
    def test_quoted_commas_nesting_shapes_and_budgets_are_not_evaluated(self):
        self.assertEqual(0, argument_shape("")["arity"])
        shapes=argument_shape('"a,b",-7,1.5,true,variable,nested(1,2),object.X(1)')
        self.assertEqual(7, shapes["arity"])
        self.assertEqual(["string-literal","integer-literal","number-literal","boolean-literal","name-untyped","expression-unconfirmed","expression-unconfirmed"], shapes["types"])
        for text in ('"unterminated', "([)]", "x,", "x," * 64 + "x", "(" * 17 + "x" + ")" * 17, "中" * 3000):
            self.assertEqual("unparsed",argument_shape(text)["status"])
        self.assertEqual("expression-unconfirmed",argument_shape("os.system('unsafe')")["types"][0])

    def test_private_names_and_signatures_never_appear_in_summary_or_mark_resolved(self):
        issues=[{"source":"private.cj","line":1,"record":{"_函数名字":"privateFunction","_函数参数":"privateArgument"}},
                {"source":"private.cj","line":2,"record":{"_函数名字":"privateFunction","_函数参数":"1,2"}}]
        audit=audit_functions(issues,Job())
        self.assertEqual("static-audit-only",audit["status"])
        self.assertEqual(1,audit["summary"]["unresolvedFunctions"])
        self.assertEqual(2,audit["summary"]["callSites"])
        self.assertNotIn("private",json.dumps(audit["summary"]))
        self.assertEqual("unresolved",audit["functions"][0]["status"])
        self.assertIn("side-effects",audit["functions"][0]["requiredEvidence"])

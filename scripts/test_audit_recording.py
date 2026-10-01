import unittest
from audit_recording import repeated_runs, packet_timestamps


class ReplayDetectionTest(unittest.TestCase):
    def test_dts_regression_is_detected_but_pts_reordering_is_allowed(self):
        self.assertEqual(0, packet_timestamps(["#header", "0, 0, 2", "0, 1, 0"])["backwards_dts"])
        self.assertEqual(1, packet_timestamps(["0, 2, 0", "0, 1, 2"])["backwards_dts"])

    def test_replayed_boundary_is_visible_only_with_shared_history(self):
        first = [f"packet,{i}" for i in range(100)]
        second = first[20:] + [f"packet,{i}" for i in range(100, 200)]
        self.assertEqual(0, repeated_runs(first)["longest_repeated_run"])
        self.assertEqual(0, repeated_runs(second)["longest_repeated_run"])
        self.assertEqual(80, repeated_runs(first + second)["longest_repeated_run"])

    def test_contiguous_unique_parts_do_not_trigger(self):
        self.assertEqual(0, repeated_runs(f"packet,{i}" for i in range(200))["longest_repeated_run"])


if __name__ == "__main__":
    unittest.main()

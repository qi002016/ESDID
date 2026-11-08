"""判分口径单测：列名归一、参考答案列约束、行数约束。"""

from decimal import Decimal

from app.eval.run_golden import same_result


def test_alias_order_count_matches_orders():
    model = [{"category": "手机", "order_count": 10}]
    ref = [{"category": "手机", "orders": 10}]
    assert same_result(model, ref)


def test_extra_model_columns_ignored():
    model = [{"category": "手机", "sales": 100.0, "qty": 3, "share": 0.2}]
    ref = [{"category": "手机", "sales": 100.0}]
    assert same_result(model, ref)


def test_missing_required_metric_fails():
    # 参考要 aov，模型只给了“人均消费”，不是同一个指标
    model = [{"user_type": "new", "sales": 100.0, "aov_per_user": 50.0}]
    ref = [{"user_type": "new", "sales": 100.0, "aov": 20.0}]
    assert not same_result(model, ref)


def test_wrong_number_fails():
    model = [{"category": "手机", "aov": 201.34}]
    ref = [{"category": "手机", "aov": 201.35}]
    assert not same_result(model, ref)


def test_rounding_tolerance_two_decimals():
    model = [{"category": "手机", "aov": 250.6288}]
    ref = [{"category": "手机", "aov": 250.63}]
    assert same_result(model, ref)


def test_row_count_must_match():
    model = [{"h": 1, "sales": 1.0}, {"h": 2, "sales": 2.0}]
    ref = [{"h": 1, "sales": 1.0}, {"h": 2, "sales": 2.0}, {"h": 3, "sales": 3.0}]
    assert not same_result(model, ref)


def test_grouping_key_mismatch_fails():
    model = [{"category": "手机", "sales": 1.0}]
    ref = [{"category": "电脑", "sales": 1.0}]
    assert not same_result(model, ref)


def test_decimal_compared_as_float():
    model = [{"d": "2026-12-30", "ma7": Decimal("20382.41")}]
    ref = [{"d": "2026-12-30", "ma7": 20382.41}]
    assert same_result(model, ref)


def test_empty_reference_requires_empty_rows():
    assert same_result([], [])
    assert not same_result([{"x": 1}], [])
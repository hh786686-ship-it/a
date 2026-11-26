import 'package:flutter_test/flutter_test.dart';
import 'package:r2t2_live_chinese/main.dart';

void main() {
  test('cumulative updates do not repeat text', () {
    final l = CaptionLedger();
    l.accept('大家好');
    l.accept('大家好我是罗高');
    l.accept('大家好我是罗高');
    expect(l.rows.last, '大家好我是罗高');
  });

  test('corrections do not rewrite committed characters', () {
    final l = CaptionLedger();
    l.accept('温室效率');
    l.accept('温室效应');
    expect(l.rows.last, '温室效率');
    l.accept('温室效应让地球');
    expect(l.rows.last, '温室效率让地球');
  });

  test('full rows do not change when another row grows', () {
    final l = CaptionLedger();
    const sentence = '一二三四五六七八九十甲乙丙丁戊';
    l.accept(sentence);
    final completed = l.rows[3];
    l.accept('${sentence}新字幕');
    expect(l.rows[3], completed);
    expect(l.rows.last, '新字幕');
  });

  test('restart clears previous captions', () {
    final l = CaptionLedger();
    l.accept('旧字幕');
    l.reset();
    expect(l.rows.every((row) => row.isEmpty), isTrue);
    l.accept('新的字幕');
    expect(l.rows.last, '新的字幕');
    expect(l.rows.join(), isNot(contains('旧字幕')));
  });
}

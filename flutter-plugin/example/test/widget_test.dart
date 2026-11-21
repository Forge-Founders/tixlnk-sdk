// This is a basic Flutter widget test.
//
// To perform an interaction with a widget in your test, use the WidgetTester
// utility in the flutter_test package. For example, you can send tap and scroll
// gestures. You can also use WidgetTester to find child widgets in the widget
// tree, read text, and verify that the values of widget properties are correct.

import 'package:flutter_test/flutter_test.dart';

import 'package:txlnk_adtech_sdk_example/main.dart';

void main() {
  testWidgets('example starts with the SDK flow controls', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const ExampleApp());

    expect(find.text('Tixlnk AdTech SDK Example'), findsOneWidget);
    expect(find.text('Init SDK'), findsOneWidget);
    expect(
      find.textContaining(
        'iOS reads TixlnkAppGroup when that Info.plist key is set',
      ),
      findsOneWidget,
    );
    expect(find.text('Trigger Deferred Match (matchDevice)'), findsOneWidget);
    expect(find.text('Track Purchase'), findsOneWidget);
  });
}

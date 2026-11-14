// Browsers own session cookies. Web replies never populate this list.
// This shape keeps the shared parser independent of dart:io.
class SessionCookie {
  const SessionCookie(this.name, this.value);
  final String name, value;
  DateTime? get expires => null;
  String? get domain => null;
  String? get path => null;
  int? get maxAge => null;
  bool get secure => true;
}

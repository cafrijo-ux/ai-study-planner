import 'browser_transport.dart';
import 'planner_api.dart';
import 'study_request_store_web.dart';

Transport createTransport() => BrowserTransport();
StudyRequestStore createRequestStore() => WebStudyRequestStore();

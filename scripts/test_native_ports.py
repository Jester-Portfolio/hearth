import socket
import unittest
from verify_native_economy import port_closed

class NativePortCleanupTests(unittest.TestCase):
 def test_a_listening_server_is_not_closed(self):
  with socket.socket() as listener:
   listener.bind(('127.0.0.1',0));listener.listen(1)
   self.assertFalse(port_closed(listener.getsockname()[1]))
 def test_unused_port_is_closed(self):
  with socket.socket() as listener:
   listener.bind(('127.0.0.1',0));port=listener.getsockname()[1]
  self.assertTrue(port_closed(port))
 def test_clean_server_close_in_TIME_WAIT_is_not_reported_as_running(self):
  listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen(1);port=listener.getsockname()[1]
  client=socket.create_connection(('127.0.0.1',port));accepted,_=listener.accept();listener.close();accepted.close()
  self.assertEqual(b'',client.recv(1));client.close()
  with socket.socket() as probe:
   with self.assertRaises(OSError):probe.bind(('127.0.0.1',port))
  self.assertTrue(port_closed(port))

if __name__=='__main__':unittest.main()

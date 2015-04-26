package com.vng.skygarden.game;

import com.vng.netty.*;
import com.vng.util.*;
import com.vng.log.*;
import com.vng.db.*;
import com.vng.skygarden.*;
import com.vng.skygarden._gen_.ProjectConfig;
import java.util.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.DatagramChannel;

import com.vng.echo.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

public class IControlCenter
{
	Client		_client = null;
	String		_ip = "";
	
	String		_device_id				= "";
	String		_companion_id			= "";
	boolean		_is_login				= false;
	int			_command_id 			= -1;
	int			_return_id				= -1;
	int			_request_id				= -1;
	
	int			_current_step			= -1;
	int			_time_change_step		= -1;
	
	List<String> _script; // device's action script
	byte[]		 _screenshot; // latest screenshot
	String		 _app_id = "null"; // latest app bundle id
	int			 _position_x = -1;
	int			 _position_y = -1;
	int			 _retry = 0;
	
	private final int MAX_RETRY = 5;
	private int SLEEP_TIME = 1000;
	private int _sleep_time = 0;
	boolean _finished_step = false;
	
	boolean _running = true;
	boolean _reviving = false;
	boolean _cleaning = false;
	
	public IControlCenter(Client client, String ip_address)
	{
		_client = client;
		_ip = ip_address;
	}
	
	public void MessageReceived(byte[] data)
	{
		FBEncrypt encrypt = new FBEncrypt(data.length);
		
		if (encrypt.decode(data) == false)
		{
			LogHelper.Log("MessageReceived.. err! decode data failed.");
			RequestError();
			return;
		}
		
		//get command id
		_command_id = encrypt.getShort(KeyID.KEY_USER_COMMAND_ID);
		
		if (!_is_login)
		{
			if (_command_id == CommandID.CMD_LOGIN)
			{
				HandleLogin(encrypt);
			}
		}
		else
		{
			int request_id = encrypt.getInt(KeyID.KEY_USER_REQUEST_ID);
			if (!CheckValidRequest(request_id))
			{
				LogHelper.Log("MessageReceived.. err! check valid request fail !");
				RequestError();
				return;
			}
			else
			{
				// handle main logic here
				MessageHandler(encrypt);
			}
		}
	}
	
	private void MessageHandler(FBEncrypt encrypt)
	{		
		// handles response from client
		switch (_command_id)
		{
			case CommandID.CMD_REPORT_CURRENT_STEP:
			{
				LogHelper.Log("MessageHandler.. received response for CMD_REPORT_CURRENT_STEP");
			}
				break;
				
			case CommandID.CMD_TAKE_SCREEN_SHOT:
			{
				// receive image from client
				if (encrypt.hasKey("screen_shot"))
				{
					_screenshot = encrypt.getBinary("screen_shot");
					
					if (_screenshot != null)
					{
						LogHelper.Log("MessageHandler.. received image for CMD_TAKE_SCREEN_SHOT with length = " + _screenshot.length);
					
						try
						{
							Files.write(Paths.get("./2_" + System.currentTimeMillis() + ".png"), _screenshot, StandardOpenOption.CREATE);
						}
						catch (Exception e)
						{
							e.printStackTrace();
						}
					}
				}
				else
				{
					LogHelper.Log("MessageHandler.. err! reponse for CMD_TAKE_SCREEN_SHOT does not contain image");
					_screenshot = null;
				}
			}
				break;
				
			case CommandID.CMD_PERFORM_TOUCH_SCREEN:
			{
				LogHelper.Log("MessageHandler.. received response for CMD_PERFORM_TOUCH_SCREEN");
			}
				break;
				
			case CommandID.CMD_GO_HOME_SCREEN:
			{
				LogHelper.Log("MessageHandler.. received response for CMD_GO_HOME_SCREEN");
			}
				break;
				
			case CommandID.CMD_REPORT_FRONTMOST_APP:
			{
				// get frontmost app name from clinet
				if (encrypt.hasKey("app_name"))
				{
					_app_id = encrypt.getString("app_name");
					
					LogHelper.Log("MessageHandler.. received response for CMD_REPORT_FRONTMOST_APP with app name  = [" + _app_id + "]");
				}
			}
				break;
				
			case CommandID.CMD_NOTIFY_APP_EXIT:
			{
				// this controler is going to be killed
			}
				break;
				
			case CommandID.CMD_DOUBLE_HOME_TOUCH:
			{
				LogHelper.Log("MessageHandler.. received response for CMD_DOUBLE_HOME_TOUCH");
			}
		}
	}
	
	public void ExecuteAI()
	{
		if (!ServerHandler.isControllerOnline(_companion_id) && !_reviving && !_cleaning)
		{
			ExecuteReviveProtocol();
		}
		
		if (_current_step >= _script.size())
			return;
		
		// decides what to do next base on _current_step
		String[] line = _script.get(_current_step).split(":");
		
		if (_reviving)
			LogHelper.Log("ATTENTION!!! RUNNING REVIVING PROTOCOL.");
		
		String command = line[0];
		if (command.equals("#"))
		{
			LogHelper.Log("info! comment at line " + _script.get(_current_step));
			
			IncreaseStep();
		}
		else
		{
			LogHelper.Log("");
			LogHelper.Log(Misc.getCurrentDateTime() + ": [" + _device_id.toUpperCase() + "] handling command = " + _script.get(_current_step));
			if (command.equals("HOME")) // structure: HOME;
			{
				// check if at home screen yet
				// if not, send request home touch
				if (!_finished_step)
				{
					LogHelper.Log("ExecuteAI.. ask frontmost app");
					_finished_step = true;
					
					_command_id = CommandID.CMD_REPORT_FRONTMOST_APP;
					FBEncrypt encoder = new FBEncrypt();
					encoder.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());

					try
					{
						_client.WriteZip(encoder.toByteArray());
						LogHelper.Log("ExecuteAI.. reponse to client OK.");
					}
					catch (Exception ex) 
					{
						LogHelper.LogException("ExecuteAI", ex);
					}
				}
				else
				{
					if (_app_id.equals(""))
					{
						LogHelper.Log("ExecuteAI.. at home screen. Finish step.");
						_finished_step = false;
						IncreaseStep();
					}
					else
					{
						LogHelper.Log("ExecuteAI.. not at home screen. Frontmost app = [" + _app_id + "]. Send home screen command.");
						_finished_step = false;
						
						_command_id = CommandID.CMD_GO_HOME_SCREEN;
						FBEncrypt encoder = new FBEncrypt();
						encoder.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());

						try
						{
							_client.WriteZip(encoder.toByteArray());
							LogHelper.Log("ExecuteAI.. reponse to client OK.");
						} 
						catch (Exception ex) 
						{
							LogHelper.LogException("ExecuteAI", ex);
						}
					}
				}
			}
			else if (command.equals("WAIT")) // structure: WAIT:seconds;
			{
				// do not use thread sleep here
//				// thread sleep seconds
//				try
//				{
//					Thread.sleep(Long.parseLong(line[1]) * 1000);
//				}
//				catch (Exception e)
//				{
//					LogHelper.LogException("ExecuteAI.WAIT", e);
//				}
				int sleep_time = Integer.parseInt(line[1]);
				if (_sleep_time < sleep_time)
				{
					_sleep_time++;
					LogHelper.Log("WAIT... sleep time = " + _sleep_time);
				}
				else
				{
					IncreaseStep();
					_sleep_time = 0;
				}
			}
			else if (command.equals("TOUCH")) // structure: TOUCH:position_x:position_y;
			{
				try
				{
					IncreaseStep();
					
					// send request touch with position_x, position_y
					int x = Integer.parseInt(line[1]);
					int y = Integer.parseInt(line[2]);
					
					_command_id = CommandID.CMD_PERFORM_TOUCH_SCREEN;

					FBEncrypt encoder = new FBEncrypt();
					encoder.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());
					encoder.addInt("position_x", x);
					encoder.addInt("position_y", y);

					_client.WriteZip(encoder.toByteArray());
					LogHelper.Log("ExecuteAI.. reponse to client OK.");
				} 
				catch (Exception ex) 
				{
					LogHelper.LogException("ExecuteAI.TOUCH", ex);
				}
			}
			else if (command.equals("FIND")) // structure: FIND:target_file:partition;
			{
				if (_screenshot == null) // request newest screenshot
				{
					_command_id = CommandID.CMD_TAKE_SCREEN_SHOT;
					
					FBEncrypt encoder = new FBEncrypt();
					encoder.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());

					try
					{
						_client.WriteZip(encoder.toByteArray());
						LogHelper.Log("ExecuteAI.. reponse to client OK.");
					} 
					catch (Exception ex) 
					{
						LogHelper.LogException("ExecuteAI", ex);
					}
				}
				else
				{
					try
					{
						String target = line[1];
						int partition = Integer.parseInt(line[2]);
						
						if (FindImage(target, _screenshot, partition)) // proceed to next step
						{
							IncreaseStep();
							_screenshot = null;
							_retry = 0;
							
//							_command_id = CommandID.CMD_REPORT_CURRENT_STEP;
						}
						else // ask client for another screenshot and repeat this step
						{
							_retry++;
							LogHelper.Log("err! can not find image. retry = " + _retry);
							
							_command_id = CommandID.CMD_TAKE_SCREEN_SHOT;
							
							// response to client
							FBEncrypt encoder = new FBEncrypt();
							encoder.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());

							_client.WriteZip(encoder.toByteArray());
							LogHelper.Log("ExecuteAI.. reponse to client OK.");
						}
					}
					catch (Exception e)
					{
						LogHelper.LogException("ExecuteAI.FIND", e);
					}
				}
			}
			else if (command.equals("FIND_AND_RESET_IF_NOT_FOUND")) // structure: FIND_AND_RESET_IF_NOT_FOUND:source_position:target_file;
			{
				// as find, if not found, reset the progress
				if (_screenshot == null) // request newest screenshot
				{
					_command_id = CommandID.CMD_TAKE_SCREEN_SHOT;
					
					FBEncrypt encoder = new FBEncrypt();
					encoder.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());

					try
					{
						_client.WriteZip(encoder.toByteArray());
						LogHelper.Log("ExecuteAI.. reponse to client OK.");
					} 
					catch (Exception ex) 
					{
						LogHelper.LogException("ExecuteAI", ex);
					}
				}
				else
				{
					try
					{
						String target = line[1];
						
						BufferedImage img_target = extractBufferedImage(target);
						BufferedImage img_source = extractBufferedImage(_screenshot);
						BufferedImage img_source_partial = gePartialBufferedImage(img_source, 4);
						
						int img_target_w = img_target.getWidth();
						int img_target_h = img_target.getHeight();

						boolean found = false;

						OUTMOST: for (int y = 0; y < img_source_partial.getHeight() && !found; y++)
						{
							INNER_1: for (int x = 0; x < img_source_partial.getWidth() && !found; x++)
							{
								BufferedImage sample = null;
								try
								{
									sample = img_source_partial.getSubimage(x, y, img_target_w, img_target_h);
								}
								catch (Exception ex)
								{
									break INNER_1;
								}

								if (isEqual(sample, img_target))
								{
									found = true;
									_position_x = (x + img_target_w/2)/2;
									_position_y = (y + img_target_h/2)/2;
									LogHelper.Log("Found match position at [" + _position_x + "," + _position_y + "].");
								}
							}
						}
						
						if (found) // proceed to next step
						{
							IncreaseStep();
							_screenshot = null;
							_retry = 0;
						}
						else // ask client for another screenshot and repeat this step
						{
							_retry++;
							LogHelper.Log("err! can not find image. retry = " + _retry);
							
							if (_retry >= MAX_RETRY)
							{
								LogHelper.Log("err critical!!! can not find image after max retry, reset the progress.");
								SetStep(0);
								_retry = 0;
								_screenshot = null;
							}
							
							_command_id = CommandID.CMD_TAKE_SCREEN_SHOT;
					
							FBEncrypt encoder = new FBEncrypt();
							encoder.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());

							try
							{
								_client.WriteZip(encoder.toByteArray());
								LogHelper.Log("ExecuteAI.. reponse to client OK.");
							} 
							catch (Exception ex) 
							{
								LogHelper.LogException("ExecuteAI", ex);
							}
						}
					}
					catch (Exception e)
					{
						LogHelper.LogException("ExecuteAI.FIND", e);
					}
				}
			}
			else if (command.equals("CLICK_CURRENT_TARGET")) // structure: CLICK_CURRENT_TARGET;
			{
				IncreaseStep();
				
				// click current _x, _y
				_command_id = CommandID.CMD_PERFORM_TOUCH_SCREEN;
				FBEncrypt encoder = new FBEncrypt();
				encoder.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());
				encoder.addInt("position_x", _position_x);
				encoder.addInt("position_y", _position_y);
				
				try
				{
					_client.WriteZip(encoder.toByteArray());
					LogHelper.Log("ExecuteAI.. reponse to client OK.");
				}
				catch (Exception e)
				{
					LogHelper.LogException("ExecuteAI.TOUCH", e);
				}
			}
			else if (command.equals("GO_TO_STEP")) // structure: GO_TO_STEP:step;
			{
				// assign step to _current_step
				try
				{
					int step = Integer.parseInt(line[1]);
					SetStep(step);
				}
				catch (Exception e)
				{
					LogHelper.LogException("ExecuteAI.GO_TO_STEP", e);
				}
			}
			else if (command.equals("TAKE_SCREEN_SHOT")) // structure: TAKE_SCREEN_SHOT;
			{
				try
				{
					IncreaseStep();
					_command_id = CommandID.CMD_TAKE_SCREEN_SHOT;
					
					FBEncrypt encoder = new FBEncrypt();
					encoder.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());

					try
					{
						_client.WriteZip(encoder.toByteArray());
						LogHelper.Log("TAKE_SCREEN_SHOT.. reponse to client OK.");
					} 
					catch (Exception ex) 
					{
						LogHelper.LogException("ExecuteAI", ex);
					}
				}
				catch (Exception e)
				{
					LogHelper.LogException("ExecuteAI.GET_SCREEN_SHOOT", e);
				}
			}
			else if (command.equals("FIND_ADVANCE")) // structure: FIND_ADVANCE:target_file:partition:step_if_found:step_if_NOT_found;
			{
				if (_screenshot == null) // request newest screenshot
				{
					_command_id = CommandID.CMD_TAKE_SCREEN_SHOT;
					
					FBEncrypt encoder = new FBEncrypt();
					encoder.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());

					try
					{
						_client.WriteZip(encoder.toByteArray());
						LogHelper.Log("FIND_ADVANCE.. request screenshot OK.");
					} 
					catch (Exception ex) 
					{
						LogHelper.LogException("ExecuteAI", ex);
					}
				}
				else
				{
					try
					{
						String target = line[1];
						int partition = Integer.parseInt(line[2]);

						int step_if_found = -1;
						if (line[3].equals("NEXT"))			step_if_found = _current_step + 1;
						if (line[3].equals("CURRENT"))		step_if_found = _current_step; 
						if (line[3].equals("PREVIOUS"))		step_if_found = _current_step - 1;
						if (step_if_found == -1)			step_if_found = Integer.parseInt(line[3]);
							
						
						int step_if_not_found = -1;
						if (line[4].equals("NEXT"))			step_if_not_found = _current_step + 1;
						if (line[4].equals("CURRENT"))		step_if_not_found = _current_step;
						if (line[4].equals("PREVIOUS"))		step_if_not_found = _current_step - 1;
						if (step_if_not_found == -1)		step_if_not_found = Integer.parseInt(line[4]);
							

						if (FindImage(target, _screenshot, partition))
						{
							SetStep(step_if_found);
							_screenshot = null;
							_retry = 0;
						}
						else
						{
							if (_retry >= 3)
							{
								LogHelper.Log("FIND_ADVANCE.. can not find image. retry = " + _retry);
								SetStep(step_if_not_found);
								_screenshot = null;
								_retry = 0;
							}
							else
							{
								_retry++;
								LogHelper.Log("FIND_ADVANCE.. can not find image. retry = " + _retry);
								
								_command_id = CommandID.CMD_TAKE_SCREEN_SHOT;
								
								FBEncrypt encoder = new FBEncrypt();
								encoder.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());
								
								_client.WriteZip(encoder.toByteArray());
								LogHelper.Log("FIND_ADVANCE.. request screenshot OK.");
							}
						}
					}
					catch (Exception e)
					{
						LogHelper.LogException("ExecuteAI.FIND", e);
					}
				}
			}
			else if (command.equals("FIND_UNTIL_NOT_FOUND")) // structure: FIND_UNTIL_NOT_FOUND:target:partition;
			{
				if (_screenshot == null) // request newest screenshot
				{
					_command_id = CommandID.CMD_TAKE_SCREEN_SHOT;
					
					FBEncrypt encoder = new FBEncrypt();
					encoder.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());

					try
					{
						_client.WriteZip(encoder.toByteArray());
						LogHelper.Log("FIND_UNTIL_NOT_FOUND.. request screenshot OK.");
					} 
					catch (Exception ex) 
					{
						LogHelper.LogException("ExecuteAI", ex);
					}
				}
				else
				{
					try
					{
						String target = line[1];
						int partition = Integer.parseInt(line[2]);
						
						if (!FindImage(target, _screenshot, partition)) // if not found
						{
							_retry++;
							LogHelper.Log("FIND_UNTIL_NOT_FOUND.. can not find image, retry = " + _retry);
							
							if (_retry < 3)
							{
								_command_id = CommandID.CMD_TAKE_SCREEN_SHOT;
							
								FBEncrypt encoder = new FBEncrypt();
								encoder.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());

								_client.WriteZip(encoder.toByteArray());
								LogHelper.Log("FIND_UNTIL_NOT_FOUND.. request screenshot OK.");
							}
							else
							{
								IncreaseStep();
								_screenshot = null;
								_retry = 0;
							}
						}
						else
						{
							_command_id = CommandID.CMD_TAKE_SCREEN_SHOT;
							
							FBEncrypt encoder = new FBEncrypt();
							encoder.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());

							_client.WriteZip(encoder.toByteArray());
							LogHelper.Log("FIND_UNTIL_NOT_FOUND.. request screenshot OK.");
						}
					}
					catch (Exception e)
					{
						LogHelper.LogException("FIND_UNTIL_NOT_FOUND", e);
					}
				}
			}
			else if (command.equals("HOME_TOUCH")) // structure: HOME_TOUCH;
			{
				IncreaseStep();
				
				_command_id = CommandID.CMD_GO_HOME_SCREEN;
				FBEncrypt encoder = new FBEncrypt();
				encoder.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());

				try
				{
					_client.WriteZip(encoder.toByteArray());
					LogHelper.Log("HOME_TOUCH.. reponse to client OK.");
				} 
				catch (Exception ex) 
				{
					LogHelper.LogException("ExecuteAI", ex);
				}
			}
			else if (command.equals("DOUBLE_HOME_TOUCH")) // structure: DOUBLE_HOME_TOUCH;
			{
				IncreaseStep();
				
				_command_id = CommandID.CMD_DOUBLE_HOME_TOUCH;
				FBEncrypt encoder = new FBEncrypt();
				encoder.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());

				try
				{
					_client.WriteZip(encoder.toByteArray());
					LogHelper.Log("CMD_DOUBLE_HOME_TOUCH.. reponse to client OK.");
				} 
				catch (Exception ex) 
				{
					LogHelper.LogException("ExecuteAI", ex);
				}
			}
			else if (command.equals("EXIT")) // structure: EXIT;
			{
				IncreaseStep();
				
				_command_id = CommandID.CMD_EXIT;
				FBEncrypt encoder = new FBEncrypt();
				encoder.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());

				try
				{
					_client.WriteZip(encoder.toByteArray());
					LogHelper.Log("EXIT.. reponse to client OK.");
				} 
				catch (Exception ex) 
				{
					LogHelper.LogException("EXIT", ex);
				}
			}
		}
		
		if (_reviving && _current_step == _script.size())
		{
			// finished cleaning||reviving, restart AI
			LogHelper.Log("Finished reviving. Restart AI.");
			RestartAI();
		}
		
		if (_retry > MAX_RETRY)
		{
			LogHelper.Log("ExecuteAI.. critial err! reset round");
			_retry = 0;
			SetStep(0);
		}
		
		if (Misc.SECONDS() > _time_change_step + 120)
		{
			ExecuteCleanProtocol();
		}
	}
	
	private void RequestError()
	{
		if (_return_id == ReturnCode.RESPONSE_OK)
		{
			_return_id = ReturnCode.RESPONSE_ERROR;
		}
		
		FBEncrypt enc = new FBEncrypt();
		enc.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());
		
		try
		{
			_client.WriteZip(enc.toByteArray());
			LogHelper.Log("***** Server returned ERROR code to client: " + _return_id + " *****");
		}
		catch (Exception ex) 
		{
			LogHelper.LogException("RequestError", ex);
		}
	}
	
	private void RequestLogin()
	{
		_command_id = CommandID.CMD_REQUEST_LOGIN;
		_return_id = ReturnCode.RESPONSE_REQUIRE_LOGIN;
		
		RequestError();
	}
	
	public byte[] GetResponseStatus()
	{
		FBEncrypt responseInfos = new FBEncrypt();

		responseInfos.addInt(KeyID.KEY_USER_COMMAND_ID, _command_id);
		responseInfos.addInt(KeyID.KEY_USER_REQUEST_STATUS, _return_id);
		responseInfos.addInt(KeyID.KEY_USER_REQUEST_ID, _request_id);
		
		return responseInfos.toByteArray();
	}
	
	private void HandleLogin(FBEncrypt encrypt)
	{
		// load this device's action script
		_script = new LinkedList<String>();
		
		if (encrypt.hasKey(KeyID.KEY_DEVICE_ID))
		{
			_device_id = encrypt.getString(KeyID.KEY_DEVICE_ID);
		}
		else
		{
			LogHelper.Log("err! login package does not contain device id");
			RequestError();
			return;
		}
		
		if (_device_id.contains("_support")) // if supporter dies, call the main controller to revive the supporter
			_companion_id = _device_id.replace("_support", "");
		else // if main controller dies, call the supporter to revive him
			_companion_id = _device_id + "_support";
		
		LoadScript("./../script" + "_" + _device_id + ".script");
		
		_is_login = true;
		ServerHandler.AddController(_device_id, this);
		SetStep(0);
		
//		FBEncrypt encoder = new FBEncrypt();
//		encoder.addBinary(KeyID.KEY_REQUEST_STATUS, GetResponseStatus());
//		
//		try
//		{
//			_client.WriteZip(encoder.toByteArray());
//			LogHelper.Log("HandleLogin.. reponse to client OK.");
//		} 
//		catch (Exception ex) 
//		{
//			LogHelper.LogException("HandleLogin", ex);
//		}
	}
	
	private void SetStep(int step)
	{
		if (_current_step != step)
		{
			_current_step = step;
			_time_change_step = Misc.SECONDS();
		}
	}
	
	private void IncreaseStep()
	{
		_current_step++;
		_time_change_step = Misc.SECONDS();
	}
	
	private boolean CheckValidRequest(int request_id)
	{
//		if (request_id < _request_id)
//		{
//			LogHelper.Log("checkValidRequest.. err! check valid request: failed!");
//			LogHelper.Log("checkValidRequest.. client request id:	" + request_id);
//			LogHelper.Log("checkValidRequest.. server request id:	" + _request_id);
//			_return_id = ReturnCode.RESPONSE_WRONG_REQUEST_ID;
//			return false;
//		}
		
		_request_id++;
		
		return true;
	}
	
	public String GetDeviceID()
	{
		return _device_id;
	}
	
	private boolean LoadScript(String name)
	{
		LogHelper.Log("###Loadscript with script name: " + name);
		_script.clear();
		
		Path path = Paths.get(name);
		try
        {
            BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
            String line = null;

            while ((line = reader.readLine()) != null)
            {
				if (line.endsWith(";"))
				{
					_script.add(line.toUpperCase().replace(";", ""));
				}
				else
				{
					LogHelper.Log("LoadScript.. checking valid script failed: " + line + " at " + _script.size());
				}
            }

            reader.close();
			
			LogHelper.Log("LoadScript.. load script done. Total command: " + _script.size());
			return true;
        }
        catch (Exception e)
        {
            LogHelper.LogException("LoadScript.ReadScript", e);
			return false;
        }
	}
	
	public void ExecuteReviveProtocol()
	{
		LogHelper.Log("ExecuteReviveProtocol.. start.");
		LoadScript("./../script" + "_" + _device_id + "_revive.script");
		_reviving = true;
		SLEEP_TIME = 5000;
		SetStep(0);
		
		_cleaning = false;
		_running = false;
	}
	
	public void ExecuteCleanProtocol()
	{
		LogHelper.Log("ExecuteCleanProtocol.. start.");
		LoadScript("./../script" + "_" + _device_id + "_clean.script");
		_cleaning = true;
		SLEEP_TIME = 5000;
		SetStep(0);
		
		_running = false;
		_reviving = false;
	}
	
	public void RestartAI()
	{
		LogHelper.Log("RestartAI.. start.");
		LoadScript("./../script" + "_" + _device_id + ".script");
		_running = true;
		SLEEP_TIME = 1000;
		SetStep(0);
		
		_reviving = false;
		_cleaning = false;
	}
	
	public void CheckActivity()
	{
		if (Misc.SECONDS() > _time_change_step + 60)
		{
			RestartAI();
		}
		else
		{
			LogHelper.Log("CheckActivity.. OK!");
		}
	}
	
	public static BufferedImage extractBufferedImage(String name) throws Exception
	{
		return ImageIO.read(new File("./../png/" + name + ".png"));
	}
	
	public static BufferedImage extractBufferedImage(byte[] aob) throws Exception
	{
		return ImageIO.read(new ByteArrayInputStream(aob));
	}
	
		public static byte[] extractBytes(String ImageName) throws Exception 
	{
		Path path = Paths.get("./../png/" + ImageName + ".png");
		byte[] data = Files.readAllBytes(path);
		return data;
	}
		
	public boolean FindImage(String target, byte[] source, int partition) throws Exception
	{
		BufferedImage img_target = extractBufferedImage(target);
		BufferedImage img_source = extractBufferedImage(source);
		BufferedImage img_source_partial = null;

		if (partition > 0 && partition < 9)
		{
			img_source_partial = gePartialBufferedImage(img_source, partition);
		}
		else
		{
			img_source_partial = img_source;
		}

		int img_target_w = img_target.getWidth();
		int img_target_h = img_target.getHeight();

		boolean found = false;

		OUTMOST: for (int y = 0; y < img_source_partial.getHeight() && !found; y++)
		{
			INNER_1: for (int x = 0; x < img_source_partial.getWidth() && !found; x++)
			{
				BufferedImage sample = null;
				try
				{
					sample = img_source_partial.getSubimage(x, y, img_target_w, img_target_h);
				}
				catch (Exception ex)
				{
					break INNER_1;
				}

				if (isEqual(sample, img_target))
				{
					found = true;
					_position_x = (x + img_target_w/2)/2;
					_position_y = (y + img_target_h/2)/2;
					LogHelper.Log("Found match position at [" + _position_x + "," + _position_y + "].");
				}
			}
		}
		
		return found;
	}
		
	public static boolean isEqual(BufferedImage actual, BufferedImage expect)
	{
		boolean equal = true;
		
		for (int x = 0; x < actual.getWidth() && equal; x ++)
		{
			for (int y = 0; y < actual.getHeight() && equal; y ++)
			{
				if (actual.getRGB(x, y) != expect.getRGB(x, y))
				{
					equal = false;
				}
			}
		}
		
		return equal;
	}
	
	/*
	 * |--------|--------|		|-----------------|		|--------|--------|
	 * |	1	|	2	 |		|		 5		  |		|		 |		  |
	 * |--------|--------|		|-----------------|		|	 7	 |	  8	  |
	 * |	3	|	4	 |		|		 6		  |		|		 |		  |
	 * |--------|--------|		|-----------------|		|-----------------|
	 */
	
	public static BufferedImage gePartialBufferedImage(BufferedImage bi, int type)
	{
		int x = 0;
		int y = 0;
		int w = bi.getWidth() >> 0x01;
		int h = bi.getHeight() >> 0x01;
		switch (type)
		{
			case 1:
				x = 0;
				y = 0;
				break;
			case 2:
				x = w;
				y = 0;
				break;
			case 3:
				x = 0;
				y = h;
				break;
			case 4:
				x = w;
				y = h;
				break;
			case 5:
				x = 0;
				y = 0;
				w <<= 0x01;
				break;
			case 6:
				x = 0;
				y = h;
				w <<= 0x01;
				break;
			case 7:
				x = 0;
				y = 0;
				h <<= 0x01;
				break;
			case 8:
				x = w;
				y = 0;
				h <<= 0x01;
				break;
			default:
				return null;
		}
		
		return bi.getSubimage(x, y, w, h);
	}
	
	
}
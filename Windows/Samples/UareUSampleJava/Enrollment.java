import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import com.digitalpersona.uareu.*;

public class Enrollment 
	extends JPanel
	implements ActionListener
{
	
	public class EnrollmentThread 
		extends Thread
		implements Engine.EnrollmentCallback
	{
		public static final String ACT_PROMPT   = "enrollment_prompt";
		public static final String ACT_CAPTURE  = "enrollment_capture";
		public static final String ACT_FEATURES = "enrollment_features";
		public static final String ACT_DONE     = "enrollment_done";
		public static final String ACT_CANCELED = "enrollment_canceled";
		
		public class EnrollmentEvent extends ActionEvent{
			private static final long serialVersionUID = 102;

			public Reader.CaptureResult capture_result;
			public Reader.Status        reader_status;
			public UareUException       exception;
			public Fmd                  enrollment_fmd;
			
			public EnrollmentEvent(Object source, String action, Fmd fmd, Reader.CaptureResult cr, Reader.Status st, UareUException ex){
				super(source, ActionEvent.ACTION_PERFORMED, action);
				capture_result = cr;
				reader_status = st;
				exception = ex;
				enrollment_fmd = fmd;
			}
		}
		
		private final Reader   m_reader;
		private CaptureThread  m_capture;
		private ActionListener m_listener;
		private boolean m_bCancel;
		
		protected EnrollmentThread(Reader reader, ActionListener listener){
			m_reader = reader;
			m_listener = listener;
		}
		
		public Engine.PreEnrollmentFmd GetFmd(Fmd.Format format){
			Engine.PreEnrollmentFmd prefmd = null;

			while(null == prefmd && !m_bCancel){
				//start capture thread
				m_capture = new CaptureThread(m_reader, false, Fid.Format.ANSI_381_2004, Reader.ImageProcessing.IMG_PROC_DEFAULT);
				m_capture.start(null);
				
				//prompt for finger
				SendToListener(ACT_PROMPT, null, null, null, null);
				
				//wait till done
				m_capture.join(0);
				
				//check result
				CaptureThread.CaptureEvent evt = m_capture.getLastCaptureEvent();
				if(null != evt.capture_result){
					if(Reader.CaptureQuality.CANCELED == evt.capture_result.quality){
						//capture canceled, return null
						break;
					}
					else if(null != evt.capture_result.image && Reader.CaptureQuality.GOOD == evt.capture_result.quality){
						//acquire engine
						Engine engine = UareUGlobal.GetEngine();
							
						try{
							//extract features
							Fmd fmd = engine.CreateFmd(evt.capture_result.image, Fmd.Format.ANSI_378_2004);
								
							//return prefmd 
							prefmd = new Engine.PreEnrollmentFmd();
							prefmd.fmd = fmd;
							prefmd.view_index = 0;
								
							//send success
							SendToListener(ACT_FEATURES, null, null, null, null);
						}
						catch(UareUException e){ 
							//send extraction error
							SendToListener(ACT_FEATURES, null, null, null, e);
						}
					}
					else{
						//send quality result
						SendToListener(ACT_CAPTURE, null, evt.capture_result, evt.reader_status, evt.exception);
					}
				}
				else{
					//send capture error
					SendToListener(ACT_CAPTURE, null, evt.capture_result, evt.reader_status, evt.exception);
				}
			}
			
			return prefmd;
		}
		
		public void cancel(){
			m_bCancel = true;
			if(null != m_capture) m_capture.cancel();
		}
		
		private void SendToListener(String action, Fmd fmd, Reader.CaptureResult cr, Reader.Status st, UareUException ex){
			if(null == m_listener || null == action || action.equals("")) return;

			final EnrollmentEvent evt = new EnrollmentEvent(this, action, fmd, cr, st, ex);
			
			//invoke listener on EDT thread
	        try {
				javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
				    public void run() {
						m_listener.actionPerformed(evt);
				    }
				});
			} 
	        catch (InvocationTargetException e) { e.printStackTrace(); } 
	        catch (InterruptedException e) { e.printStackTrace(); }
		}
		
		public void run(){
			//acquire engine
			Engine engine = UareUGlobal.GetEngine();
			
			try{
				m_bCancel = false;
				while(!m_bCancel){
					//run enrollment
					Fmd fmd = engine.CreateEnrollmentFmd(Fmd.Format.ANSI_378_2004, this);
					
					//send result
					if(null != fmd){
						SendToListener(ACT_DONE, fmd, null, null, null);
					}
					else{
						SendToListener(ACT_CANCELED, null, null, null, null);
						break;
					}
				}
			}
			catch(UareUException e){ 
				SendToListener(ACT_DONE, null, null, null, e);
			}
		}
	}
	
	
	private static final long serialVersionUID = 6;
	private static final String ACT_BACK = "back";

	private EnrollmentThread m_enrollment;
	private Reader  m_reader;
	private JDialog m_dlgParent;
	private JTextArea m_text;
	private boolean m_bJustStarted;
	
	private Enrollment(Reader reader){
		m_reader = reader;
		m_bJustStarted = true;
		m_enrollment = new EnrollmentThread(m_reader, this);
	
		final int vgap = 5;
		final int width = 380;
		
		BoxLayout layout = new BoxLayout(this, BoxLayout.Y_AXIS);
		setLayout(layout);
		
		m_text = new JTextArea(22, 1);
		m_text.setEditable(false);
		JScrollPane paneReader = new JScrollPane(m_text);
		add(paneReader);
		Dimension dm = paneReader.getPreferredSize();
		dm.width = width;
		paneReader.setPreferredSize(dm);
		
		add(Box.createVerticalStrut(vgap));
		
		JButton btnBack = new JButton("Back");
		btnBack.setActionCommand(ACT_BACK);
		btnBack.addActionListener(this);
		add(btnBack);
		add(Box.createVerticalStrut(vgap));
	
		setOpaque(true);
	}
	
	public void actionPerformed(ActionEvent e){
		if(e.getActionCommand().equals(ACT_BACK)){
			//destroy dialog to cancel enrollment
			m_dlgParent.setVisible(false);
		}
		else{
			EnrollmentThread.EnrollmentEvent evt = (EnrollmentThread.EnrollmentEvent)e;
			
			if(e.getActionCommand().equals(EnrollmentThread.ACT_PROMPT)){
				if(m_bJustStarted){
					m_text.append("Enrollment started\n");
					m_text.append("    put any finger on the reader\n");
				}
				else{
					m_text.append("    put the same finger on the reader\n");
				}
				m_bJustStarted = false;
			}
			else if(e.getActionCommand().equals(EnrollmentThread.ACT_CAPTURE)){
				if(null != evt.capture_result){
					MessageBox.BadQuality(evt.capture_result.quality);
				}
				else if(null != evt.exception){
					MessageBox.DpError("Capture", evt.exception);
				}
				else if(null != evt.reader_status){
					MessageBox.BadStatus(evt.reader_status);
				}
				m_bJustStarted = false;
			}
			else if(e.getActionCommand().equals(EnrollmentThread.ACT_FEATURES)){
				if(null == evt.exception){
					m_text.append("    fingerprint captured, features extracted\n\n");
				}
				else{
					MessageBox.DpError("Feature extraction", evt.exception);
				}
				m_bJustStarted = false;
			}
			else if(e.getActionCommand().equals(EnrollmentThread.ACT_DONE)){
				if(null == evt.exception){
					String str = String.format("    enrollment template created, size: %d\n\n\n", evt.enrollment_fmd.getData().length);
					m_text.append(str);
				}
				else{
					MessageBox.DpError("Enrollment template creation", evt.exception);
				}
				m_bJustStarted = true;
			}
			else if(e.getActionCommand().equals(EnrollmentThread.ACT_CANCELED)){
				//canceled, destroy dialog
				m_dlgParent.setVisible(false);
			}
			
			//cancel enrollment if any exception or bad reader status
			if(null != evt.exception){
				m_dlgParent.setVisible(false);
			}
			else if(null != evt.reader_status && Reader.ReaderStatus.READY != evt.reader_status.status && Reader.ReaderStatus.NEED_CALIBRATION != evt.reader_status.status){
				m_dlgParent.setVisible(false);
			}
		}
	}
	
	private void doModal(JDialog dlgParent){
		try { main(null); } catch (Exception e) { e.printStackTrace(); }
		return;
	}
	
	public static void Run(Reader reader){
		JDialog dlg = new JDialog((JDialog)null, "Enrollment", true);
		Enrollment enrollment = new Enrollment(reader);
		enrollment.doModal(dlg);
	}

	/**
	 * Register a fingerprint received from the frontend JavaScript API.
	 *
	 * @param base64Samples  4 base64URL-encoded intermediate-format (DP_PRE_REG_FEATURES) samples
	 *                       taken from the {@code Data} field of each JS sample object
	 * @param socio          member ID to store in tbhuellas
	 * @param dedo           finger index (0-9) to store in tbhuellas
	 * @throws UareUException if FMD import or enrollment creation fails
	 * @throws java.sql.SQLException if the database INSERT fails
	 */
	public static void main(String[] args) throws Exception {
		String[] samples = {
			"AOg3Acgp43NcwEE381mKq9lcZ2YLbuhS8izeLNGuXQhuHqAujtJqo0k8VkPf0UAXD2UKHvK8gXOGp6WSoe4n5jMKN2ER397WMj0zmbUTvxvmVyHfUGB_K2rhzEkcUK6mBjSgDR2cZU5LMm5po5iN_Ww-YquJ_TcEMQs0EFUa6chBYolCO7bHHuzuWCWeI3d7_94xD1TqmFs-bQg9ssCV_7n8tk9Y0zGAfBYjfA2gLMESKH4VJCGzkOF-CmdRXOB20cLlbMNIr8cGzgGL8XavMVU8QElWj0upHZsbJi-tsQiA1Z9RKu63DZhM2sr7dUDXPa0bp4mIjQRSwB4ouxHUFF-7ZqZ-CXRGe5DDdlf3mk1GGKsF98jNLAY8ymc2cCCROI211PkMlfjowUWqp4a7xO2Qo-b2NuFN167qbwAA",
			"AOg5Acgp43NcwEE381mK695cZ2byKp7AT8a5alEjvwfFFn8e3aaVLk0KYTXOoaQgxqUnA4oGmpBHZQfpeDoZ7lkMNCk1IZwqb2_NCxWBG1tbWwjLhTMeijTi6MOUSSt9FJUnKc4-hA4RoqLKiRY1h3Lx_9z6x6NMVldClERUEf2IgfWgDRbK9jOAJOjG1KHIxgyRr0XFSAkrmozyFhm-CXdyFTHToO9PILVGFpWV-BWZKJmdcameu6RvnOFYpwfKx2UxGS8l3ZrREq5jSj3tNOixnTdhAFqFDdMthWTice8lir1QRa3ZDf7y1ALBr2hFkZjtrqqyARUtNQsI5wALxLvTLyaa1-cuuOU0r5ja7cQQ4wwTyg0-f6a0TJ_rTXd6vdt3--0gbSr_O42rhNiPgsseKV7zSVs_2L3ItAZv",
			"AOg0Acgp43NcwEE381mKq9ZcZ2ZuS_GqxGiph_CFWAshVKxxhxjdBrnng5WZg7PhpMGU4yVEgitLgnRBzl3B30I9kouTEe6Yn3w-2MhkvG5IgqmA5wW4uY7MLvpBXb6MN3qYinLhotoILrDQzQouLjiEvXDKzQrxyaFczrDSrrNgvxBd9B6rMz6QexT9_mldugCKWkBT97h1_sxIAYwHn_-TEz4Pj3fbvojUEIHWbUqP-27tMxUZa3XLrZUeGBtGQQHMk5GQexKyo-MfsOXmGrASI0CtLZdJ6iHgUlVfPxrOkpJb7EDQVoa6SXy1Sqzw8qdj--pggnMCb1wNSF-91vvVoZkpur3IXPYdAwslUa5SXT8oyHOM6lMcXNDaYRVbLs2PsPkSfEYs3W_GFguBDwaVoZZhhyZubwAAAAAA",
			"AOg5Acgp43NcwEE381mKqzFdZ2ZxDx506Ek7e1Aa00oK4cyqDdgF0VSHAeb5IGsPgUwmDiL-1z3rQk_2HwtS-7tYYuVf22nGNZO651fwb1yUBYpGZy1AkFzUxI1SiI820vgLzgEz2NA17JOW27IZ__A0DXy8KC4vxs7z3VDHHN2ZKqFJSGsNwiXIwzQuWzv-1VJFGHwQzsYNzX46CmMvyO5uW929ThCA52DB9I90Vx1Xk9kofiOfvtaael9j-7Nozn6l6MUlNJxtBfEhcJ7KIUxv55Lwd_kUVHvVLHdEFn-74N25lyTGR0mnHzAqqUIsFDr1mpeDlpDr3Vhi0kwwRvoI1DNM2QzlS7PWYIj0F99FH06MT3vEhfbnb0_PkXYWo_tD7d8YpGhZekvLBpO7MPORG704mFQOBH_GAzpv"
		};
		registerFromWeb(samples, 9063, 2);
	}

	public static void registerFromWeb(String[] base64Samples, int socio, int dedo)
			throws UareUException, java.sql.SQLException {

		Importer importer = UareUGlobal.GetImporter();
		Engine   engine   = UareUGlobal.GetEngine();

		// 1. Decode each base64URL sample and import as DP_PRE_REG_FEATURES
		final Engine.PreEnrollmentFmd[] preFmds = new Engine.PreEnrollmentFmd[base64Samples.length];
		for (int i = 0; i < base64Samples.length; i++) {
			byte[] bytes = java.util.Base64.getUrlDecoder().decode(base64Samples[i]);
			Fmd fmd = importer.ImportFmd(
					bytes,
					Fmd.Format.DP_PRE_REG_FEATURES,
					Fmd.Format.DP_PRE_REG_FEATURES);
			Engine.PreEnrollmentFmd pre = new Engine.PreEnrollmentFmd();
			pre.fmd        = fmd;
			pre.view_index = 0;
			preFmds[i]     = pre;
		}

		// 2. Inline EnrollmentCallback that serves the pre-imported FMDs one by one
		final int[] cursor = {0};
		Engine.EnrollmentCallback callback = new Engine.EnrollmentCallback() {
			@Override
			public Engine.PreEnrollmentFmd GetFmd(Fmd.Format format) {
				if (cursor[0] < preFmds.length) {
					return preFmds[cursor[0]++];
				}
				return null; // signal the engine that no more samples are available
			}
		};

		// 3. Create the DP_REG_FEATURES enrollment template
		Fmd enrollmentFmd = engine.CreateEnrollmentFmd(Fmd.Format.DP_REG_FEATURES, callback);
		if (enrollmentFmd == null) {
			throw new IllegalStateException("CreateEnrollmentFmd returned null — not enough samples or enrollment was cancelled");
		}

		// 4. Convert bytes to ISO-8859-1 string (same encoding used by loadFmdsFromDatabase)
		String huellaStr = new String(enrollmentFmd.getData(), StandardCharsets.ISO_8859_1);

		// 5. INSERT into tbhuellas
		String url  = "jdbc:mysql://194.238.29.232:3307/bdksiste_bdkgym"
				    + "?useSSL=false&characterEncoding=ISO-8859-1";
		String user = "root";
		String pass = "Fum4s!Crick0Fu+Maryjuana";

		try (java.sql.Connection conn = java.sql.DriverManager.getConnection(url, user, pass);
			 java.sql.PreparedStatement ps = conn.prepareStatement(
				 "INSERT INTO tbhuellas (socio, dedo, huella) VALUES (?, ?, ?)")) {
			ps.setInt(1, socio);
			ps.setInt(2, dedo);
			ps.setString(3, huellaStr);
			ps.executeUpdate();
			System.out.println("Registered fingerprint for socio=" + socio + " dedo=" + dedo
					+ " fmd_size=" + enrollmentFmd.getData().length);
		}
	}
}

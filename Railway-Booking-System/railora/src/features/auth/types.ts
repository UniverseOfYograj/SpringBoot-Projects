export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  message: string;
}

export interface UserResponse {
  userId: number;
  username: string;
  fullName: string;
  email: string;
  phoneNumber: string | null;
  status: string;
  createdAt: string;
}

export interface LoginCredentials {
  username: string;
  password: string;
}

export interface RegistrationDetails {
  username: string;
  fullName: string;
  email: string;
  phoneNumber: string;
  password: string;
}
